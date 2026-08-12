#!/usr/bin/env bash
# ============================================================================
# 부하 테스트 동안 호스트(맥) CPU 를 샘플링해 CSV 로 남긴다.
#
# == 왜 필요한가 ==
# 단일 맥에서는 부하 생성기(JMeter)와 측정 대상(서비스 6개 + Docker)이
# 같은 CPU 를 나눠 쓴다. 호스트가 포화되면 지연이 늘어나는데, 그건
# "서버가 느린 것"이 아니라 "부하기가 요청을 제때 못 보낸 것"일 수 있다.
# 이 둘을 구분하지 못하면 측정 전체가 무의미해진다.
#
# 판정 기준(계획 A2): 부하 구간 CPU 70% 초과 → 그 회차는 폐기.
#
# == 왜 Grafana 가 아니라 CSV 인가 ==
# macOS 에서 node_exporter 를 Docker 로 띄우면 리눅스 VM 의 CPU 를 재지
# 호스트 맥의 CPU 를 재지 못한다. 호스트를 직접 재려면 호스트에서 도는
# 프로세스여야 한다.
#
# 사용법:
#   ./cpu_sampler.sh out.csv 660      # 660초 동안 1초 간격 샘플링
#   ./cpu_sampler.sh                  # 기본 ./cpu.csv, 무한 (Ctrl-C 로 종료)
# ============================================================================
set -uo pipefail

OUT="${1:-./cpu.csv}"
DURATION="${2:-0}"          # 0 이면 무한
INTERVAL="${INTERVAL:-1}"

NCPU=$(sysctl -n hw.ncpu)

echo "epoch,user_pct,sys_pct,idle_pct,busy_pct,load1" > "$OUT"
echo "🖥️  CPU 샘플링 시작 (코어 $NCPU 개, ${INTERVAL}초 간격) → $OUT"

# run.sh 가 부하 종료 후 kill 로 이 스크립트를 멈춘다. 트랩이 없으면 그 자리에서
# 즉사해서 아래 요약이 영영 안 찍힌다(실제로 첫 회차에서 그랬다).
# 플래그만 세우고 루프를 정상 종료시켜 요약까지 마치게 한다.
STOP=0
trap 'STOP=1' TERM INT

start=$(date +%s)
while :; do
  [ "$STOP" -eq 1 ] && break
  now=$(date +%s)
  if [ "$DURATION" -gt 0 ] && [ $((now - start)) -ge "$DURATION" ]; then
    break
  fi

  # top -l 2 로 두 샘플을 뜬다. 첫 샘플은 부팅 이후 누적 평균이라 쓸모없고
  # 두 번째부터가 구간 값이다. -l 1 만 쓰면 항상 같은 숫자가 나온다.
  line=$(top -l 2 -n 0 -s "$INTERVAL" 2>/dev/null | grep "^CPU usage" | tail -1)
  # 형식: CPU usage: 12.5% user, 6.25% sys, 81.25% idle
  usr=$(echo "$line" | sed -n 's/.*: *\([0-9.]*\)% user.*/\1/p')
  sys=$(echo "$line" | sed -n 's/.*user, *\([0-9.]*\)% sys.*/\1/p')
  idl=$(echo "$line" | sed -n 's/.*sys, *\([0-9.]*\)% idle.*/\1/p')
  [ -z "$usr" ] && continue

  busy=$(echo "$usr $sys" | awk '{printf "%.2f", $1 + $2}')
  load1=$(sysctl -n vm.loadavg | awk '{print $2}')

  echo "$(date +%s),$usr,$sys,$idl,$busy,$load1" >> "$OUT"
done

echo ""
echo "📊 CPU 요약 ($OUT)"
awk -F, 'NR>1 {
  n++; s+=$5; if ($5>mx) mx=$5; a[n]=$5
}
END {
  if (n==0) { print "  샘플 없음"; exit }
  # p95 를 위해 정렬
  for (i=1;i<=n;i++) for (j=i+1;j<=n;j++) if (a[i]>a[j]) { t=a[i]; a[i]=a[j]; a[j]=t }
  p95 = a[int(n*0.95) < 1 ? 1 : int(n*0.95)]
  printf "  샘플 %d개  평균 %.1f%%  p95 %.1f%%  최대 %.1f%%\n", n, s/n, p95, mx
  if (p95 > 70) print "  ⚠️  p95 가 70%% 를 넘었다 → 이 회차는 폐기 대상. 부하기가 병목일 수 있다."
  else          print "  ✅ p95 70%% 미만 → 측정 유효."
}' "$OUT"
