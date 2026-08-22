#!/usr/bin/env bash
# ============================================================
# Comatching5 로컬 실행 스크립트
#   ./run-local.sh          -> 인프라 + 전체 서비스 기동
#   ./run-local.sh --skip-build   -> 빌드 생략하고 기동
#   ./run-local.sh --infra-only   -> 인프라(도커)만 기동
#   ./run-local.sh --with-monitoring -> Prometheus/Grafana 까지 기동
#   ./run-local.sh --perf-s1  -> 부하 테스트 S1 최소 구성만 기동
#                                (mysql + redis + gateway + user-service)
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
cd "$(dirname "$0")"

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

# ---------- 1. 환경변수 로드 ----------
if [ ! -f .env ]; then
  echo "❌ .env 파일이 없습니다."
  exit 1
fi
set -a
# shellcheck disable=SC1091
source .env
set +a
echo "✅ .env 로드 완료"

# ---------- 2. 인프라 기동 ----------
if [ "$PERF_S1" = true ]; then
  echo "🐳 [perf-s1] 인프라 최소 기동 (mysql/redis)..."
  docker compose -f docker-compose.local.yml up -d mysql redis
  # 이미 떠 있으면 내린다. down 이 아니라 stop 이라 데이터는 남는다.
  echo "🛑 [perf-s1] kafka/mongo 정지 (S1 경로에 없음, 메모리 약 1.2GB 회수)"
  docker compose -f docker-compose.local.yml stop kafka mongo 2>/dev/null || true
else
  echo "🐳 인프라 컨테이너 기동 중 (mysql/redis/mongo/kafka)..."
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

# 이미 떠 있는 서비스를 먼저 내린다.
#
# 왜 필요한가: 이게 없으면 새 JVM 이 포트 바인딩에 실패해 죽는데,
# wait_for_port 는 "포트가 열려 있다"만 확인하므로 옛 프로세스를 보고
# "기동 완료"라고 보고한다. 그런데 Hibernate 의 ddl-auto: create 는
# 포트 바인딩보다 먼저 돌아서 스키마를 이미 드롭·재생성한 뒤다.
# 결과: 시드 데이터만 사라지고 옛 코드가 계속 돈다. 실제로 겪었다.
stop_existing() {
  local found=false pids
  for entry in "${SERVICES[@]}"; do
    IFS=':' read -r name _ port <<< "$entry"
    pids=$(lsof -ti tcp:"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
      echo "🛑 포트 $port 사용 중이던 프로세스 종료 ($name): $(echo $pids | tr '\n' ' ')"
      kill $pids 2>/dev/null || true
      found=true
    fi
  done
  if [ "$found" = true ]; then
    sleep 3
    for entry in "${SERVICES[@]}"; do
      IFS=':' read -r _ _ port <<< "$entry"
      pids=$(lsof -ti tcp:"$port" 2>/dev/null || true)
      [ -n "$pids" ] && kill -9 $pids 2>/dev/null || true
    done
    sleep 1
  fi
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
  echo "   ⚠️  $name 이 90초 안에 뜨지 않았습니다. logs/$name.log 확인"
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
  echo "   이 상태로 부하 테스트를 돌리면 안 된다."
  echo "   user-service 는 ddl-auto: create 라, 기동에 실패해도 스키마는 이미"
  echo "   드롭·재생성된 뒤다. 즉 시드 데이터가 날아가 있다."
  echo "   원인을 고치고 다시 띄운 뒤 tools/perf/seed/load_seed.sh 를 다시 실행하라."
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
