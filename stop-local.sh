#!/usr/bin/env bash
# 전체 서비스 종료. --all 옵션 시 도커 인프라까지 내림.
set -uo pipefail
cd "$(dirname "$0")"

PID_DIR="./.pids"
ROOT="$(pwd)"
if [ -d "$PID_DIR" ]; then
  for pidfile in "$PID_DIR"/*.pid; do
    [ -e "$pidfile" ] || continue
    name=$(basename "$pidfile" .pid)
    pid=$(cat "$pidfile")
    command_line=$(ps -p "$pid" -o args= 2>/dev/null || true)
    case "$command_line" in
      *"$ROOT/$name/build/libs/"*)
        kill "$pid" && echo "🛑 $name (pid $pid) 종료"
        ;;
    esac
    rm -f "$pidfile"
  done
fi

if [ "${1:-}" = "--all" ]; then
  if [ -f docker-compose.monitoring.yml ]; then
    docker compose -f docker-compose.monitoring.yml down
    echo "📊 모니터링 스택 종료"
  fi
  docker compose -f docker-compose.local.yml down
  echo "🐳 인프라 종료"
fi

echo "🏁 완료"
