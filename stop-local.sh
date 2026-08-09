#!/usr/bin/env bash
# 전체 서비스 종료. --all 옵션 시 도커 인프라까지 내림.
set -uo pipefail
cd "$(dirname "$0")"

PID_DIR="./.pids"
if [ -d "$PID_DIR" ]; then
  for pidfile in "$PID_DIR"/*.pid; do
    [ -e "$pidfile" ] || continue
    name=$(basename "$pidfile" .pid)
    pid=$(cat "$pidfile")
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" && echo "🛑 $name (pid $pid) 종료"
    fi
    rm -f "$pidfile"
  done
fi

# 혹시 남은 프로세스 정리
for port in 8080 9000 9001 9003 9005 9006; do
  pid=$(lsof -ti tcp:"$port" 2>/dev/null || true)
  [ -n "$pid" ] && kill -9 $pid 2>/dev/null && echo "🛑 포트 $port 프로세스 정리"
done

if [ "${1:-}" = "--all" ]; then
  if [ -f docker-compose.monitoring.yml ]; then
    docker compose -f docker-compose.monitoring.yml down
    echo "📊 모니터링 스택 종료"
  fi
  docker compose -f docker-compose.local.yml down
  echo "🐳 인프라 종료"
fi

echo "🏁 완료"
