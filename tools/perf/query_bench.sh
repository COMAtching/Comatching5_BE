#!/usr/bin/env bash
# ============================================================================
# 쿼리 하나를 반복 실행해 소요 시간을 재고, 실행 계획과 함께 기록한다.
#
# == 왜 도구로 만드나 ==
# 개선 전후를 비교하려면 두 측정이 같은 조건이어야 한다. 손으로 재면
# 워밍업 횟수나 반복 수가 달라져서 비교가 성립하지 않는다.
# 결과를 TSV 로 누적하므로 나중에 회차 간 비교가 그대로 된다.
#
# == 함정 두 가지 ==
# 1) 첫 실행은 버퍼 풀이 비어 있어 디스크 I/O 가 섞인다.
#    -> 워밍업 3회를 별도 세션에서 돌리고 버린다.
# 2) MySQL 8 에는 쿼리 캐시가 없다(8.0 에서 제거). 같은 쿼리를 반복해도
#    매번 실제로 실행되므로 반복 측정이 유효하다. 5.7 이었다면 무효였다.
#
# == 단일 쿼리 시간 != 부하 상황 시간 ==
# 여기서 재는 건 "경합 없는 상태의 쿼리 비용"이다. 동시 요청이 몰리면
# CPU 경합으로 늘어난다. 회차 1 에서 19ms 쿼리가 200 RPS 에서 500ms 가 됐다.
# 이 값은 부하 테스트를 대체하지 않고, 부하 테스트 결과를 해석하는 근거다.
#
# 사용법:
#   ./query_bench.sh -l "before" 'SELECT COUNT(*) FROM comatching_user.members WHERE ...'
#   ./query_bench.sh -n 30 -l "after-index" 'SELECT ...'
#
#   # 인덱스가 이미 있을 때, 지우지 않고 "인덱스 없는 경우"를 재려면
#   ./query_bench.sh -x idx_role_status -l "before" 'SELECT COUNT(*) FROM comatching_user.members WHERE ...'
#
# 결과는 docs/perf/query-bench.tsv 에 누적된다.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/../.."

CONTAINER="${CONTAINER:-comatching-mysql}"
MYSQL_PW="${MYSQL_ROOT_PASSWORD:-comatching12!@}"
N=20
LABEL="-"
IGNORE_IDX=""
LOG="docs/perf/query-bench.tsv"

usage() { sed -n '2,30p' "$0"; exit 1; }

while getopts ":n:l:x:h" opt; do
  case "$opt" in
    n) N="$OPTARG" ;;
    l) LABEL="$OPTARG" ;;
    x) IGNORE_IDX="$OPTARG" ;;
    h|*) usage ;;
  esac
done
shift $((OPTIND - 1))
SQL="${1:-}"
[ -n "$SQL" ] || usage

# -x 를 주면 FROM <table> 뒤에 IGNORE INDEX 를 끼워넣는다.
# 인덱스를 실제로 DROP 하지 않고 "인덱스 없는 실행 계획"을 재현할 수 있어
# 스키마를 건드리지 않고 개선 전 수치를 얻을 수 있다.
if [ -n "$IGNORE_IDX" ]; then
  SQL=$(printf '%s' "$SQL" | sed -E "s/(FROM[[:space:]]+[A-Za-z0-9_.\`]+)/\1 IGNORE INDEX ($IGNORE_IDX)/I")
fi

mysql_q() { docker exec -i "$CONTAINER" mysql -uroot -p"$MYSQL_PW" "$@" 2>&1 | grep -v "Using a password"; }

echo "════════════════════════════════════════════════════════════"
echo " 라벨   : $LABEL"
echo " 반복   : ${N}회 (워밍업 3회 별도, 버림)"
[ -n "$IGNORE_IDX" ] && echo " 무시   : IGNORE INDEX ($IGNORE_IDX)"
echo " 쿼리   : $SQL"
echo "════════════════════════════════════════════════════════════"

# ---------- 실행 계획 ----------
echo ""
echo "▸ 실행 계획"
PLAN=$(mysql_q -N -B -e "EXPLAIN $SQL" | head -1)
echo "$PLAN" | awk -F'\t' '{printf "  type=%-6s key=%-18s rows=%-8s filtered=%-7s %s\n", $4,$6,$9,$10,$11}'

# ---------- 워밍업 (버퍼 풀) ----------
WARM=""
for _ in 1 2 3; do WARM+="$SQL;"; done
mysql_q -e "$WARM" > /dev/null

# ---------- 측정 ----------
BODY=""
for _ in $(seq 1 "$N"); do BODY+="$SQL;"; done
RAW=$(mysql_q -e "SET profiling=1; $BODY SHOW PROFILES;" | awk -F'\t' 'NF>=3 && $1 ~ /^[0-9]+$/ {print $2}')

echo ""
echo "▸ 소요 시간"
STATS=$(printf '%s\n' "$RAW" | sort -n | awk -v n="$N" '
  {v[NR]=$1*1000}
  END {
    if (NR==0) { print "0 0 0 0 0"; exit }
    p50=v[int(NR*0.50)?int(NR*0.50):1]; p95=v[int(NR*0.95)?int(NR*0.95):1]
    s=0; for(i=1;i<=NR;i++) s+=v[i]
    printf "%.2f %.2f %.2f %.2f %.2f", v[1], p50, p95, v[NR], s/NR
  }')
read -r MIN P50 P95 MAX MEAN <<< "$STATS"
printf "  min %.2f ms   p50 %.2f ms   p95 %.2f ms   max %.2f ms   평균 %.2f ms\n" \
  "$MIN" "$P50" "$P95" "$MAX" "$MEAN"

# ---------- 기록 ----------
mkdir -p "$(dirname "$LOG")"
[ -f "$LOG" ] || printf "when\tlabel\tn\tmin_ms\tp50_ms\tp95_ms\tmax_ms\tmean_ms\tplan\tsql\n" > "$LOG"
printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
  "$(date '+%Y-%m-%d %H:%M:%S')" "$LABEL" "$N" "$MIN" "$P50" "$P95" "$MAX" "$MEAN" \
  "$(echo "$PLAN" | awk -F'\t' '{print "type="$4",key="$6",rows="$9}')" \
  "$(printf '%s' "$SQL" | tr -s ' ')" >> "$LOG"

echo ""
echo "▸ 누적 기록 ($LOG)"
column -t -s $'\t' "$LOG" | cut -c1-150
