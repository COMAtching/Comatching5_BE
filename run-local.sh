#!/usr/bin/env bash
# ============================================================
# Comatching5 로컬 실행 스크립트
#   ./run-local.sh          -> 인프라 + 전체 서비스 기동
#   ./run-local.sh --skip-build   -> 빌드 생략하고 기동
#   ./run-local.sh --infra-only   -> 로컬 MySQL 확인/DB 생성 + Docker 인프라 기동
#   ./run-local.sh --with-monitoring -> Prometheus/Grafana 까지 기동
#   ./run-local.sh --perf-s1  -> 부하 테스트 S1 최소 구성만 기동
#                                (호스트 MySQL + Docker Redis + gateway + user-service)
#
# --perf-s1 를 왜 만들었나
#   이 맥은 메모리 8GB 다. 1회차 부하 테스트에서 서비스 6개 + 컨테이너 7개
#   + JMeter 를 동시에 올렸더니 압축 메모리 3.3GB, 스왑 5.9GB 가 되면서
#   호스트 CPU 가 100% 에 박혔고, 측정값 전체가 무효가 됐다.
#   S1(GET /api/auth/participants)이 실제로 지나는 경로는
#   gateway -> user-service -> MySQL 뿐이다. 나머지는 측정 대상이 아니면서
#   메모리만 먹는다(Kafka 혼자 1.05GB). Redis 는 게이트웨이의 토큰
#   denylist 조회에 필요해서 남긴다.
# ============================================================
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

ROOT="$(pwd)"
LOG_DIR="$ROOT/logs"
PID_DIR="$ROOT/.pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

SKIP_BUILD=false
INFRA_ONLY=false
WITH_MONITORING=false
PERF_S1=false
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=true ;;
    --infra-only) INFRA_ONLY=true ;;
    --with-monitoring) WITH_MONITORING=true ;;
    --perf-s1) PERF_S1=true; WITH_MONITORING=true ;;   # 측정이 목적이므로 모니터링은 필수
  esac
done

# ---------- 1. 로컬 환경변수 / JDK ----------
if [ ! -f .env.local ]; then
  echo "❌ .env.local 파일이 없습니다. .env.local.example을 복사하고 로컬 키를 채우세요."
  exit 1
fi
set -a
# shellcheck disable=SC1091
source .env.local
set +a
if [ "${SPRING_PROFILES_ACTIVE:-}" != local ]; then
  echo "❌ run-local.sh는 로컬 인프라 전용입니다. .env.local에 SPRING_PROFILES_ACTIVE=local을 설정하세요."
  echo "   AWS 실행은 .env.prod를 읽는 Docker Compose 또는 별도 IDE 실행 구성을 사용하세요."
  exit 1
fi
if [ -z "${JWT_SECRET:-}" ] || [ -z "${INTERNAL_SERVICE_TOKEN:-}" ]; then
  echo "❌ .env.local의 JWT_SECRET / INTERNAL_SERVICE_TOKEN을 로컬 전용 값으로 채우세요."
  exit 1
fi
if [ "$(uname -s)" = Darwin ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 17)
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if ! java -version 2>&1 | grep -q 'version "17\.'; then
  echo "❌ JDK 17이 필요합니다. JAVA_HOME을 확인하세요."
  exit 1
fi

# MySQL은 Docker로 만들지 않는다. 노트북 설치본에 같은 설정으로 접속한다.
export LOCAL_MYSQL_HOST="${LOCAL_MYSQL_HOST:-127.0.0.1}"
export LOCAL_MYSQL_PORT="${LOCAL_MYSQL_PORT:-3306}"
export LOCAL_MYSQL_USERNAME="${LOCAL_MYSQL_USERNAME:-root}"
export LOCAL_MYSQL_PASSWORD="${LOCAL_MYSQL_PASSWORD:-}"
local_mysql() {
  MYSQL_PWD="$LOCAL_MYSQL_PASSWORD" mysql --no-defaults --protocol=TCP \
    --host="$LOCAL_MYSQL_HOST" --port="$LOCAL_MYSQL_PORT" \
    --user="$LOCAL_MYSQL_USERNAME" --connect-timeout=5 "$@"
}
if ! command -v mysql >/dev/null 2>&1; then
  echo "❌ mysql 클라이언트가 없습니다. 설치된 MySQL의 bin 경로를 PATH에 추가하세요."
  exit 1
fi
if ! local_mysql --batch --skip-column-names -e 'SELECT 1' >/dev/null; then
  echo "❌ 로컬 MySQL 연결 실패: $LOCAL_MYSQL_HOST:$LOCAL_MYSQL_PORT"
  echo "   MySQL을 실행하고 .env.local의 LOCAL_MYSQL_* 설정을 확인하세요."
  echo "   Homebrew 설치본: brew services start mysql"
  exit 1
fi
# init에는 반복 실행 가능한 생성 SQL만 둔다. 기존 스키마 변경은 별도 migration으로 관리한다.
for init_sql in database/init/*.sql; do
  local_mysql < "$init_sql"
done
echo "✅ 호스트 MySQL 준비 완료 ($LOCAL_MYSQL_HOST:$LOCAL_MYSQL_PORT)"

if ! docker info >/dev/null 2>&1; then
  echo "❌ Docker 엔진에 연결할 수 없습니다. Docker Desktop을 실행하세요."
  exit 1
fi
echo "✅ .env.local / JDK 17 준비 완료 (Spring $SPRING_PROFILES_ACTIVE 프로파일)"

# ---------- 2. 인프라 기동 ----------
if [ "$PERF_S1" = true ]; then
  echo "🐳 [perf-s1] Docker Redis 기동 (MySQL은 호스트 설치본 사용)..."
  docker compose -f docker-compose.local.yml up -d redis
  # 이미 떠 있으면 내린다. down 이 아니라 stop 이라 데이터는 남는다.
  echo "🛑 [perf-s1] kafka/mongo 정지 (S1 경로에 없음, 메모리 약 1.2GB 회수)"
  docker compose -f docker-compose.local.yml stop kafka mongo 2>/dev/null || true
else
  echo "🐳 인프라 컨테이너 기동 중 (redis/mongo/kafka)..."
  docker compose -f docker-compose.local.yml up -d
fi

echo "⏳ 인프라 헬스체크 대기..."
for i in $(seq 1 60); do
  UNHEALTHY=$(docker compose -f docker-compose.local.yml ps --format '{{.Health}}' | grep -cv '^healthy$' || true)
  if [ "$UNHEALTHY" -eq 0 ]; then
    echo "✅ 인프라 준비 완료"
    break
  fi
  sleep 3
  if [ "$i" -eq 60 ]; then
    echo "⚠️  일부 컨테이너가 healthy 상태가 아닙니다. 아래 상태를 확인하세요."
    docker compose -f docker-compose.local.yml ps
    exit 1
  fi
done

# ---------- 2-1. 모니터링 스택 ----------
# 서비스는 호스트에서 java -jar 로 뜨므로 Prometheus 는 host.docker.internal 로 스크레이프한다.
if [ "$WITH_MONITORING" = true ]; then
  echo "📊 모니터링 스택 기동 중 (prometheus/grafana)..."
  docker compose -f docker-compose.monitoring.yml up -d
fi

if [ "$INFRA_ONLY" = true ]; then
  echo "🏁 인프라만 기동했습니다."
  if [ "$WITH_MONITORING" = true ]; then
    echo "   Prometheus   : http://localhost:9090/targets"
    echo "   Grafana      : http://localhost:3001  (admin/admin)"
  fi
  exit 0
fi

# ---------- 3. 빌드 ----------
if [ "$SKIP_BUILD" = false ]; then
  echo "🔨 Gradle 빌드 (테스트 제외)..."
  ./gradlew clean build -x test
else
  echo "⏭️  빌드 생략"
fi

# ---------- 4. 서비스 기동 ----------
# 이름:모듈:포트
if [ "$PERF_S1" = true ]; then
  # S1 이 지나는 경로만. Kafka 가 꺼져 있어도 문제없는 이유:
  # 프로듀서는 첫 send 때 지연 생성되고, participants 는 이벤트를 발행하지 않는다.
  # NewTopic/KafkaAdmin 빈도 없어서 기동 시 브로커에 붙지 않는다.
  SERVICES=(
    "user-service:user-service:9000"
    "gateway-service:gateway-service:8080"
  )
else
  SERVICES=(
    "user-service:user-service:9000"
    "matching-service:matching-service:9001"
    "chat-service:chat-service:9003"
    "notification:notification:9005"
    "item-service:item-service:9006"
    "gateway-service:gateway-service:8080"
  )
fi

# 이 프로젝트가 기록한 PID만 종료한다. 다른 앱이 포트를 쓰면 중단한다.
stop_existing() {
  local pid command_line
  for entry in "${SERVICES[@]}"; do
    IFS=':' read -r name module port <<< "$entry"
    pid=$(cat "$PID_DIR/$name.pid" 2>/dev/null || true)
    if [ -n "$pid" ]; then
      command_line=$(ps -p "$pid" -o args= 2>/dev/null || true)
      case "$command_line" in
        *"$ROOT/$module/build/libs/"*)
          echo "🛑 기존 $name 종료 (pid $pid)"
          kill "$pid" 2>/dev/null || true
          for i in $(seq 1 20); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 1
          done
          if kill -0 "$pid" 2>/dev/null; then
            echo "❌ $name 종료가 지연됩니다. logs/$name.log를 확인하세요."
            return 1
          fi
          ;;
      esac
      rm -f "$PID_DIR/$name.pid"
    fi
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "❌ 포트 $port를 다른 프로세스가 사용 중입니다. 자동으로 종료하지 않습니다."
      return 1
    fi
  done
}

start_service() {
  local name="$1" module="$2" port="$3"
  local jar
  jar=$(ls "$ROOT/$module/build/libs/"*.jar 2>/dev/null | grep -v 'plain' | head -1 || true)
  if [ -z "$jar" ]; then
    echo "❌ $name jar 을 찾을 수 없습니다. --skip-build 없이 다시 실행하세요."
    return 1
  fi
  echo "🚀 $name (:$port) 기동..."
  nohup java -jar "$jar" > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$PID_DIR/$name.pid"
}

wait_for_port() {
  local name="$1" port="$2"
  local pid
  pid=$(cat "$PID_DIR/$name.pid" 2>/dev/null || echo "")
  for i in $(seq 1 90); do
    # 포트가 열렸는지보다 "내가 띄운 프로세스가 살아 있는지"를 먼저 본다.
    # 이게 죽었는데 포트가 열려 있으면 다른(옛) 프로세스가 잡고 있는 것이고,
    # 그대로 두면 옛 코드를 새 코드로 착각한 채 측정하게 된다.
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      echo "   ❌ $name 프로세스(pid $pid)가 죽었습니다."
      grep -a -m1 -A2 "APPLICATION FAILED TO START" "$LOG_DIR/$name.log" 2>/dev/null | sed 's/^/      /'
      grep -a -m1 "Port .* was already in use\|Caused by" "$LOG_DIR/$name.log" 2>/dev/null | sed 's/^/      /'
      echo "      전체 로그: logs/$name.log"
      return 1
    fi
    if nc -z localhost "$port" 2>/dev/null; then
      echo "   ✅ $name 기동 완료 (:$port, pid $pid)"
      return 0
    fi
    sleep 2
  done
  echo "   ⚠️  $name 이 180초 안에 뜨지 않았습니다. logs/$name.log 확인"
  return 1
}

stop_existing

FAILED=()
for entry in "${SERVICES[@]}"; do
  IFS=':' read -r name module port <<< "$entry"
  start_service "$name" "$module" "$port"
  wait_for_port "$name" "$port" || FAILED+=("$name")
done

if [ ${#FAILED[@]} -gt 0 ]; then
  echo ""
  echo "❌ 기동 실패: ${FAILED[*]}"
  echo "   logs/<service>.log에서 원인을 확인하세요. 로컬 스키마 설정은 update입니다."
  exit 1
fi

echo ""
echo "🏁 전체 기동 완료"
echo "   Gateway      : http://localhost:8080"
echo "   user-service : http://localhost:9000  (swagger: /swagger-ui/index.html)"
if [ "$WITH_MONITORING" = true ]; then
  echo "   Prometheus   : http://localhost:9090/targets"
  echo "   Grafana      : http://localhost:3001  (admin/admin)"
fi
echo "   로그          : tail -f logs/<service>.log"
echo "   종료          : ./stop-local.sh"
