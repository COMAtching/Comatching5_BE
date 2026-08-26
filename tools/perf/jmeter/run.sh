#!/usr/bin/env bash
# ============================================================================
# 부하 테스트 1회차 실행기.
#
# 하는 일:
#   1. 사전 점검 (JMeter / 게이트웨이 / InfluxDB / 파일 디스크립터)
#   2. 호스트 CPU 샘플러를 백그라운드로 띄움
#   3. JMeter 논-GUI 실행
#   4. 계단별 요약 + knee 판정 출력
#   5. JMeter HTML 리포트 생성
#
# 결과는 results/<플랜명>-<타임스탬프>/ 에 전부 모인다.
# 회차 기록(docs/perf-log.md)에는 이 디렉터리 이름을 적어두면 된다.
#
# 사용법:
#   ./run.sh                        # S1-participants.jmx
#   ./run.sh S2-read-mix.jmx
#   HEAP="-Xmx4g" ./run.sh
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

PLAN="${1:-S1-participants.jmx}"
[ -f "$PLAN" ] || { echo "❌ $PLAN 이 없습니다."; exit 1; }

STAMP=$(date +%Y%m%d-%H%M%S)
NAME="$(basename "$PLAN" .jmx)"
RUN_DIR="results/${NAME}-${STAMP}"
mkdir -p "$RUN_DIR"

HOST="${HOST:-localhost}"
PORT="${PORT:-8080}"
# 운영은 nginx 443 뒤라 https 로 가야 한다. 게이트웨이 8080 은 루프백
# 바인딩이라 외부에서 직접 닿지 않는다.
SCHEME="${SCHEME:-http}"
INFLUX_HOST="${INFLUX_HOST:-localhost}"

echo "════════════════════════════════════════════════════════════"
echo " 회차 실행 : $NAME"
echo " 결과 경로 : $RUN_DIR"
echo "════════════════════════════════════════════════════════════"

# ---------- 1. 사전 점검 ----------
fail=0

if ! command -v jmeter >/dev/null 2>&1; then
  echo "❌ jmeter 가 없습니다.  brew install jmeter"
  fail=1
else
  echo "✅ jmeter $(jmeter --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1)"
fi

code=$(curl -s -o /dev/null -w '%{http_code}' "$SCHEME://$HOST:$PORT/api/auth/participants" || echo 000)
if [ "$code" = "200" ]; then
  echo "✅ 게이트웨이 $HOST:$PORT — 대상 엔드포인트 200"
else
  echo "❌ 게이트웨이 응답 $code — 서비스가 떠 있는지 확인 (./run-local.sh)"
  fail=1
fi

if curl -sf "http://$INFLUX_HOST:8086/ping" >/dev/null 2>&1; then
  echo "✅ InfluxDB $INFLUX_HOST:8086"
else
  echo "❌ InfluxDB 응답 없음 — docker compose -f docker-compose.monitoring.yml up -d influxdb"
  fail=1
fi

# 파일 디스크립터. keep-alive 소켓 + JMeter 스레드가 400개면 기본 256 으로는
# 4~5 계단에서 'Too many open files' 가 터지고, 그게 서버 문제로 오독된다.
ulimit -n 65535 2>/dev/null || true
NOFILE=$(ulimit -n)
if [ "$NOFILE" -lt 10240 ]; then
  echo "⚠️  ulimit -n = $NOFILE (권장 10240 이상). 고부하 계단에서 소켓 고갈로 오독될 수 있다."
else
  echo "✅ ulimit -n = $NOFILE"
fi

# 메모리. 1회차를 통째로 날린 진짜 원인이 여기였다 — CPU 100% 는 결과였고,
# 원인은 8GB 맥에서 압축 메모리 3.3GB / 스왑 5.9GB 로 스래싱이 난 것이었다.
# pgrep/sysctl 은 macOS/리눅스 전용 — 없는 호스트(Windows Git Bash)에서는 건너뛴다
JVMS=$( (pgrep -f 'build/libs/.*\.jar' 2>/dev/null || true) | wc -l | tr -d ' ')
CONTAINERS=$(docker ps --format '{{.Names}}' | wc -l | tr -d ' ')
SWAP_USED=$( (sysctl -n vm.swapusage 2>/dev/null || true) | sed -n 's/.*used = \([0-9.]*\)M.*/\1/p')
echo "ℹ️  JVM $JVMS개 / 컨테이너 $CONTAINERS개 / 스왑 사용 ${SWAP_USED:-?}M"

if docker ps --format '{{.Names}}' | grep -qE 'comatching-(kafka|mongo)'; then
  echo "⚠️  kafka/mongo 가 떠 있다. S1 경로에 없으면서 메모리를 약 1.2GB 먹는다."
  echo "   → ./run-local.sh --perf-s1 --skip-build 로 최소 구성으로 다시 띄우는 걸 권한다."
fi
if [ "${SWAP_USED%%.*}" -gt 2048 ] 2>/dev/null; then
  echo "⚠️  스왑을 ${SWAP_USED}M 쓰고 있다. 이미 메모리가 모자란 상태이므로"
  echo "   측정하면 또 CPU 100% 로 폐기될 가능성이 높다. 재부팅 후 최소 구성으로 시작하라."
fi

[ "$fail" -eq 0 ] || { echo ""; echo "사전 점검 실패. 위 항목을 고치고 다시 실행하세요."; exit 1; }

# ---------- 2. CPU 샘플러 ----------
echo ""
SAMPLER_PID=""
if [ "$(uname -s)" = "Darwin" ]; then
  ../cpu_sampler.sh "$RUN_DIR/cpu.csv" 0 > "$RUN_DIR/cpu.log" 2>&1 &
  SAMPLER_PID=$!
  # 스크립트가 어떻게 끝나든(Ctrl-C 포함) 샘플러는 반드시 정리한다
  trap 'kill "$SAMPLER_PID" 2>/dev/null || true' EXIT
  echo "🖥️  CPU 샘플러 시작 (pid $SAMPLER_PID)"
else
  # sampler 는 macOS 전용(sysctl/top). 부하기와 대상이 다른 호스트면
  # 70% 폐기 기준의 대상은 부하기 CPU 뿐이므로 작업관리자로 관찰한다.
  echo "ℹ️  cpu_sampler 는 macOS 전용 — 이 호스트에서는 건너뜀"
fi

# ---------- 3. JMeter ----------
# 1회차엔 -Xmx2g 였는데, 8GB 맥에서는 부하기가 대상의 메모리를 뺏는 셈이었다.
# 최대 200 스레드 / 초당 수백 샘플이면 512m 로 충분하다.
export HEAP="${HEAP:--Xms256m -Xmx512m -XX:MaxMetaspaceSize=192m}"
echo ""
echo "🚀 JMeter 실행 — 워밍업 60초 + 계단 4×120초 ≈ 9분"
echo "   진행 상황은 30초마다 summariser 로그로 찍힌다."
echo ""

set +e
jmeter -n -t "$PLAN" \
  -l "$RUN_DIR/result.jtl" \
  -j "$RUN_DIR/jmeter.log" \
  -Jhost="$HOST" -Jport="$PORT" -Jscheme="$SCHEME" -JinfluxHost="$INFLUX_HOST" \
  -JtestTitle="$NAME-$STAMP" \
  -Jjmeter.save.saveservice.output_format=csv
JM_RC=$?
set -e

if [ -n "$SAMPLER_PID" ]; then
  kill "$SAMPLER_PID" 2>/dev/null || true
  wait "$SAMPLER_PID" 2>/dev/null || true
  trap - EXIT
fi

if [ "$JM_RC" -ne 0 ]; then
  echo "⚠️  JMeter 종료 코드 $JM_RC — $RUN_DIR/jmeter.log 를 확인하세요."
fi

# ---------- 4. 요약 ----------
echo ""
[ -f "$RUN_DIR/cpu.log" ] && tail -5 "$RUN_DIR/cpu.log"
# Windows Git Bash 는 python3 가 "스토어 스텁"이라 command -v 로는 못 거른다.
# 실제로 --version 이 도는 인터프리터를 고른다. cp949 콘솔 깨짐도 막는다.
PY=""
for c in python3 python; do
  if "$c" --version >/dev/null 2>&1; then PY="$c"; break; fi
done
[ -n "$PY" ] || { echo "❌ python 이 없습니다"; exit 1; }
PYTHONIOENCODING=utf-8 "$PY" summarize.py "$RUN_DIR/result.jtl" | tee "$RUN_DIR/summary.txt"

# ---------- 5. HTML 리포트 ----------
echo ""
echo "📄 HTML 리포트 생성..."
jmeter -g "$RUN_DIR/result.jtl" -o "$RUN_DIR/report" > /dev/null 2>&1 \
  && echo "   open $RUN_DIR/report/index.html" \
  || echo "   ⚠️  리포트 생성 실패 (측정값에는 영향 없음)"

echo ""
echo "🏁 완료 — $RUN_DIR"
echo "   Grafana: http://localhost:3001 → Comatching → Load Test"
