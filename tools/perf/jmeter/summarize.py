#!/usr/bin/env python3
"""
JMeter 결과(.jtl)를 계단별로 요약하고, 결정 A7 의 knee 기준을 기계적으로 적용한다.

== 왜 JMeter HTML 리포트로 안 끝내나 ==
HTML 리포트는 전체 통계를 잘 보여주지만 "어느 계단에서 무너졌는가"를
판정해주진 않는다. 눈으로 그래프를 보고 사후에 기준을 갖다 붙이면
회차마다 판정이 달라진다. 기준을 코드로 박아두면 회차 간 비교가 된다.

== 백분위는 정확히 계산한다 ==
Prometheus 히스토그램은 버킷 경계로 보간한 근사값이지만, 여기서는
원본 샘플을 전부 정렬해서 nearest-rank 로 뽑는다. 그래서 이 값과
Grafana 의 서버 측 p95 는 원래 조금 다르다. 큰 차이가 나면
그건 근사 오차가 아니라 클라-서버 gap 이다.

사용법:
    python3 summarize.py results/S1-participants-20260809-153000/result.jtl
"""

import csv
import re
import sys
from pathlib import Path

# --- 결정 A7: knee 판정 기준 ---
P95_LIMIT_MS = 500      # 서버 SLO 버킷에 있는 값
ERROR_LIMIT_PCT = 1.0
THROUGHPUT_GAIN_MIN = 1.2   # 부하 2배에 처리량이 이 배수 미만이면 포화

LABEL_RPS = re.compile(r"(\d+)rps$")


def pct(sorted_vals, p):
    """nearest-rank 백분위. p 는 0~100."""
    if not sorted_vals:
        return 0
    k = max(1, int(round(p / 100.0 * len(sorted_vals))))
    return sorted_vals[k - 1]


def main():
    if len(sys.argv) < 2:
        raise SystemExit("사용법: python3 summarize.py <result.jtl>")
    path = Path(sys.argv[1])
    if not path.exists():
        raise SystemExit(f"❌ {path} 가 없습니다.")

    groups = {}
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            label = row["label"]
            g = groups.setdefault(label, {"e": [], "err": 0, "t0": None, "t1": None, "codes": {}})
            g["e"].append(int(row["elapsed"]))
            ts = int(row["timeStamp"])
            g["t0"] = ts if g["t0"] is None else min(g["t0"], ts)
            g["t1"] = ts if g["t1"] is None else max(g["t1"], ts)
            if row["success"] != "true":
                g["err"] += 1
                code = row["responseCode"] or "?"
                g["codes"][code] = g["codes"].get(code, 0) + 1

    if not groups:
        raise SystemExit("❌ 샘플이 없습니다.")

    # WARMUP 은 집계에서 제외한다 (결정 A5)
    warmup = groups.pop("WARMUP", None)

    steps = []
    for label in sorted(groups):
        g = groups[label]
        vals = sorted(g["e"])
        n = len(vals)
        dur = max((g["t1"] - g["t0"]) / 1000.0, 0.001)
        m = LABEL_RPS.search(label)
        steps.append({
            "label": label,
            "target": int(m.group(1)) if m else None,
            "n": n,
            "err": g["err"],
            "err_pct": g["err"] * 100.0 / n,
            "tps": n / dur,
            "avg": sum(vals) / n,
            "p50": pct(vals, 50), "p90": pct(vals, 90),
            "p95": pct(vals, 95), "p99": pct(vals, 99),
            "max": vals[-1],
            "codes": g["codes"],
        })

    print()
    if warmup:
        w = sorted(warmup["e"])
        print(f"워밍업 (집계 제외)  샘플 {len(w):,}개  p95 {pct(w,95)}ms  max {max(w)}ms")
        print(f"  └ max 가 크면 정상이다. 그게 JIT·클래스로딩·풀 확장을 흡수했다는 증거다.")
        print()

    hdr = f"{'계단':<12}{'목표':>6}{'실제':>8}{'달성':>7}{'샘플':>9}{'에러%':>8}" \
          f"{'p50':>7}{'p90':>7}{'p95':>7}{'p99':>8}{'max':>8}"
    print(hdr)
    print("-" * len(hdr))
    for s in steps:
        rate = f"{s['tps']/s['target']*100:.0f}%" if s["target"] else "-"
        print(f"{s['label']:<12}{s['target'] or '-':>6}{s['tps']:>8.0f}{rate:>7}"
              f"{s['n']:>9,}{s['err_pct']:>8.2f}"
              f"{s['p50']:>7}{s['p90']:>7}{s['p95']:>7}{s['p99']:>8}{s['max']:>8}")

    # 에러 응답코드 내역
    for s in steps:
        if s["codes"]:
            detail = "  ".join(f"{k}×{v:,}" for k, v in sorted(s["codes"].items()))
            print(f"   ! {s['label']} 에러 내역: {detail}")

    # ---------- knee 판정 (결정 A7) ----------
    print()
    print(f"knee 판정  —  p95 > {P95_LIMIT_MS}ms  |  에러율 > {ERROR_LIMIT_PCT}%  |  "
          f"부하 2배에 처리량 < {THROUGHPUT_GAIN_MIN}배")
    print("-" * 78)

    knee = None
    prev = None
    for s in steps:
        reasons = []
        if s["p95"] > P95_LIMIT_MS:
            reasons.append(f"p95 {s['p95']}ms > {P95_LIMIT_MS}ms")
        if s["err_pct"] > ERROR_LIMIT_PCT:
            reasons.append(f"에러율 {s['err_pct']:.2f}% > {ERROR_LIMIT_PCT}%")
        if prev and s["target"] and prev["target"] and s["target"] == prev["target"] * 2:
            gain = s["tps"] / prev["tps"] if prev["tps"] else 0
            if gain < THROUGHPUT_GAIN_MIN:
                reasons.append(f"처리량 {gain:.2f}배 < {THROUGHPUT_GAIN_MIN}배 (포화)")

        mark = "❌" if reasons else "✅"
        print(f"  {mark} {s['label']:<12} {'; '.join(reasons) if reasons else '정상'}")
        if reasons and knee is None:
            knee = (s, reasons)
        prev = s

    print()
    if knee is None:
        last = steps[-1]
        print(f"🟢 knee 미도달. {last['target']} RPS 까지 기준을 넘지 않았다.")
        print(f"   → 계단을 더 올려야 한다. 목표 1,667 RPS 까지 최소 "
              f"{1667/last['target']:.1f}배 더 필요하다.")
    else:
        s, reasons = knee
        print(f"🔴 knee = {s['label']} (목표 {s['target']} RPS, 실제 {s['tps']:.0f} RPS)")
        print(f"   사유: {'; '.join(reasons)}")
        print(f"   목표 1,667 RPS 대비 {1667/s['tps']:.1f}배 부족")

    print()
    print("이 숫자만으로는 원인을 알 수 없다. 반드시 함께 볼 것:")
    print("  · 호스트 CPU p95 — 70% 넘으면 이 회차는 폐기 (부하기가 병목)")
    print("  · 클라(여기) p95 vs 서버(Grafana) p95 의 gap — 벌어지면 큐잉/부하기")
    print("  · Hikari pending / GC pause — 어느 자원이 먼저 찼는지")
    return 0


if __name__ == "__main__":
    sys.exit(main())
