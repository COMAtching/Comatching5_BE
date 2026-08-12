#!/usr/bin/env python3
# ============================================================================
# 매칭 1회 요청이 DB 에 지우는 부하를 SQL 레벨에서 분해 측정한다.
#
# == 무엇을 재는가 ==
# MatchingProcessor.process() 는 요청 하나에 세 종류의 쿼리를 날린다.
#   ① 제외 목록  : findMatchedMemberIdsByMemberId  (1회)
#   ② 후보 페이지 : findPotentialCandidates        (후보수/500 회)
#   ③ 취미 N+1   : @ElementCollection @BatchSize(100) 지연로딩 (후보수/100 회)
# 이 스크립트는 셋을 실제와 같은 SQL 로 같은 횟수만큼 재현하고 각각의 시간을 뗀다.
#
# == 왜 이 방식인가 ==
# 1) SHOW PROFILES 는 못 쓴다. profiling_history_size 최대가 100 인데
#    우리가 날리는 쿼리는 600 개다. 그래서 performance_schema 의
#    events_statements_summary_by_digest 를 쓴다. 같은 모양의 쿼리를
#    다이제스트로 묶어서 실행횟수/총시간/평균을 한 줄로 준다. 오히려 이 목적엔 더 맞다.
# 2) 커넥션을 하나만 연다. 쿼리마다 docker exec 를 하면 접속 비용(수십 ms)이
#    쿼리 시간(1ms 수준)을 완전히 덮는다. SQL 을 전부 만들어 한 세션에 밀어넣는다.
# 3) 키셋 페이지네이션은 이전 페이지의 마지막 id 가 있어야 다음 페이지를 만든다.
#    그래서 페이지 경계를 ROW_NUMBER() 로 미리 뽑아두고 100 개 쿼리를 한 번에 생성한다.
#    서버가 하는 일은 앱이 순차 호출할 때와 같다.
#
# == 이 값은 하한이다 (중요) ==
# 여기서 재는 건 "MySQL 이 쓴 시간"뿐이다. 실제 API 에는
#   - JPA 엔티티 5만 개 생성 + 영속성 컨텍스트 등록
#   - 자바에서 5만 번 점수 계산
#   - user-service HTTP 동기 호출 2회
# 가 더 붙는다. 그러니 실제 응답시간은 반드시 이 값보다 크다.
# 반대로 말하면, 이 값만으로도 이미 크다면 더 볼 것도 없다.
#
# 사용법:
#   python3 matching_bench.py                    # 기본 (FEMALE 대상, 페이지 500)
#   python3 matching_bench.py --page-size 5000   # 페이지 크기를 바꿔보고 싶을 때
#   python3 matching_bench.py --gender MALE
#   python3 matching_bench.py --no-hobby         # ③ 빼고 ①②만
# ============================================================================
import argparse
import os
import subprocess
import sys
import time

CONTAINER = os.environ.get("CONTAINER", "comatching-mysql")
MYSQL_PW = os.environ.get("MYSQL_ROOT_PASSWORD", "comatching12!@")
DB = "comatching_matching"
HOBBY_BATCH = 100          # MatchingCandidate.hobbyCategories 의 @BatchSize(size = 100)


def mysql(sql, tabular=False):
    """한 세션에서 sql 을 실행하고 stdout 을 돌려준다."""
    cmd = ["docker", "exec", "-i", CONTAINER, "mysql", "-uroot", f"-p{MYSQL_PW}", DB]
    cmd += ["-t"] if tabular else ["-N", "-B"]
    p = subprocess.run(cmd, input=sql, capture_output=True, text=True)
    out = "\n".join(l for l in (p.stdout or "").splitlines() if "Using a password" not in l)
    err = "\n".join(l for l in (p.stderr or "").splitlines() if "Using a password" not in l)
    if p.returncode != 0:
        print(f"❌ MySQL 오류\n{err}", file=sys.stderr)
        sys.exit(1)
    return out


def mysql_discard(sql):
    """
    결과를 컨테이너 안에서 /dev/null 로 버리며 실행한다.

    왜 필요한가: 후보 페이지 100 개는 500 행씩 총 5 만 행, 취미 배치까지 하면
    수십만 행이 나온다. 이걸 파이썬으로 받으면 파싱 비용이 쿼리 시간을 덮어버린다.
    서버는 똑같이 일하고 행도 똑같이 소켓으로 나가되, 클라이언트 쪽 비용만 없앤다.
    (앱도 결국 이 행들을 받으므로 서버 시간 측정에는 영향이 없다.)
    """
    p = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "sh", "-c",
         f"mysql -uroot -p'{MYSQL_PW}' {DB} -N -B > /dev/null"],
        input=sql, capture_output=True, text=True)
    if p.returncode != 0:
        err = "\n".join(l for l in (p.stderr or "").splitlines() if "Using a password" not in l)
        print(f"❌ MySQL 오류\n{err}", file=sys.stderr)
        sys.exit(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gender", default="FEMALE", choices=["FEMALE", "MALE"],
                    help="탐색 대상 성별. 앱은 '내 성별의 반대'를 넣는다")
    ap.add_argument("--page-size", type=int, default=500,
                    help="MatchingProcessor.MAX_CANDIDATE_FETCH_SIZE 와 같은 값")
    ap.add_argument("--no-hobby", action="store_true", help="취미 N+1 재현을 생략")
    ap.add_argument("--member-id", type=int, default=1000001,
                    help="제외 목록 조회에 쓸 회원 id")
    args = ap.parse_args()

    print("=" * 68)
    print(" 매칭 1회 요청의 DB 비용 분해")
    print("=" * 68)

    # ---------- 준비: 후보 수와 페이지 경계 ----------
    where = f"gender = '{args.gender}' AND is_matchable = 1"
    total = int(mysql(f"SELECT COUNT(*) FROM matching_candidate WHERE {where};").strip())
    if total == 0:
        print(f"❌ {args.gender} 후보가 0 명이다. 시드를 먼저 적재하라.")
        sys.exit(1)

    pages = (total + args.page_size - 1) // args.page_size
    print(f" 대상 성별   : {args.gender}  (후보 {total:,} 명)")
    print(f" 페이지 크기 : {args.page_size:,}  ->  {pages} 페이지")
    print(f" 취미 N+1    : {'생략' if args.no_hobby else f'{(total + HOBBY_BATCH - 1)//HOBBY_BATCH} 회 (@BatchSize({HOBBY_BATCH}))'}")

    # 페이지 경계 = 각 페이지의 마지막 member_id. 다음 페이지의 member_id > 경계 가 된다.
    boundaries = [int(x) for x in mysql(f"""
        SELECT member_id FROM (
          SELECT member_id, ROW_NUMBER() OVER (ORDER BY member_id) rn
          FROM matching_candidate WHERE {where}
        ) t WHERE rn MOD {args.page_size} = 0 ORDER BY member_id;
    """).split() if x]

    all_ids = [int(x) for x in mysql(
        f"SELECT member_id FROM matching_candidate WHERE {where} ORDER BY member_id;").split() if x]

    # ---------- 워밍업 ----------
    # 버퍼 풀이 비어 있으면 디스크 I/O 가 섞인다. 측정 대상 인덱스를 미리 데운다.
    print("\n▸ 버퍼 풀 워밍업...")
    mysql(f"SELECT COUNT(*) FROM matching_candidate WHERE {where};"
          "SELECT COUNT(*) FROM candidate_hobby_categories;")

    # ---------- 측정할 SQL 을 전부 만든다 ----------
    stmts = []

    # ① 제외 목록: WHERE member_id = ? OR partner_id = ?
    #    partner_id 단독 인덱스가 없어서 OR 가 인덱스를 못 탄다.
    stmts.append(
        f"SELECT DISTINCT CASE WHEN m.member_id = {args.member_id} "
        f"THEN m.partner_id ELSE m.member_id END FROM matching_history m "
        f"WHERE m.member_id = {args.member_id} OR m.partner_id = {args.member_id};")

    # ② 후보 페이지: 앱과 같은 컬럼, 같은 정렬, 같은 limit
    cols = "member_id, age, contact_frequency, gender, is_matchable, major, mbti, profile_id"
    cursors = [None] + boundaries[:pages - 1]
    for cur in cursors:
        keyset = f" AND member_id > {cur}" if cur is not None else ""
        stmts.append(f"SELECT {cols} FROM matching_candidate "
                     f"WHERE {where}{keyset} ORDER BY member_id ASC LIMIT {args.page_size};")

    # ③ 취미 N+1: Hibernate 가 IN (?, ... 100개) 로 묶어서 날린다
    if not args.no_hobby:
        for i in range(0, len(all_ids), HOBBY_BATCH):
            chunk = ",".join(str(x) for x in all_ids[i:i + HOBBY_BATCH])
            stmts.append("SELECT member_id, hobby_categories FROM candidate_hobby_categories "
                         f"WHERE member_id IN ({chunk});")

    # ---------- 실행 ----------
    print(f"▸ 쿼리 {len(stmts):,} 개 실행 중 (단일 세션)...")
    mysql("TRUNCATE performance_schema.events_statements_summary_by_digest;")

    t0 = time.perf_counter()
    mysql_discard("\n".join(stmts))
    wall_ms = (time.perf_counter() - t0) * 1000

    # ---------- 집계 ----------
    # timer_wait 단위는 피코초. /1e9 하면 ms.
    rows = mysql("""
        SELECT COUNT_STAR, ROUND(SUM_TIMER_WAIT/1e9, 2), ROUND(AVG_TIMER_WAIT/1e9, 3),
               LEFT(DIGEST_TEXT, 60)
          FROM performance_schema.events_statements_summary_by_digest
         WHERE SCHEMA_NAME = 'comatching_matching'
           AND DIGEST_TEXT LIKE 'SELECT%'
           -- mysql CLI 가 접속할 때마다 스스로 날리는 핸드셰이크 쿼리다.
           -- 우리 코드와 무관한 측정 노이즈라 뺀다.
           AND DIGEST_TEXT NOT LIKE 'SELECT @@%'
         ORDER BY SUM_TIMER_WAIT DESC;
    """)

    print("\n" + "-" * 68)
    print(f"{'실행':>7} {'총 ms':>10} {'평균 ms':>9}  쿼리")
    print("-" * 68)
    server_ms = 0.0
    nqueries = 0
    for line in rows.splitlines():
        if not line.strip():
            continue
        cnt, tot, avg, text = line.split("\t")
        if "performance_schema" in text or "ROW_NUMBER" in text:
            continue
        server_ms += float(tot)
        nqueries += int(cnt)
        label = ("① 제외 목록" if "matching_history" in text
                 else "③ 취미 컬렉션" if "candidate_hobby" in text
                 else "② 후보 페이지")
        print(f"{int(cnt):>7,} {float(tot):>10,.1f} {float(avg):>9.3f}  {label}")

    print("-" * 68)
    print(f"  SQL 서버시간 합계 : {server_ms:>9,.1f} ms   (쿼리 {nqueries:,} 회)")
    print(f"  세션 벽시계 시간  : {wall_ms:>9,.1f} ms   (프로토콜 왕복 포함)")
    print("-" * 68)

    # ---------- 실행 계획 ----------
    print("\n▸ 실행 계획 (핵심 쿼리)")
    for name, q in [
        ("② 후보 페이지",
         f"SELECT {cols} FROM matching_candidate WHERE {where} AND member_id > 1000001 "
         f"ORDER BY member_id ASC LIMIT {args.page_size}"),
        ("① 제외 목록",
         f"SELECT DISTINCT CASE WHEN m.member_id = {args.member_id} THEN m.partner_id "
         f"ELSE m.member_id END FROM matching_history m "
         f"WHERE m.member_id = {args.member_id} OR m.partner_id = {args.member_id}"),
    ]:
        plan = mysql(f"EXPLAIN {q};").splitlines()
        if plan:
            f = plan[0].split("\t")
            print(f"   {name:<14} type={f[3]:<8} key={f[5]:<22} rows={f[8]:<8} {f[10]}")

    print(f"""
{'=' * 68}
 해석 주의
{'=' * 68}
 이 숫자는 '매칭 요청 1회의 DB 비용'이고 하한이다. 실제 API 에는
   - JPA 엔티티 {total:,} 개 생성 + 영속성 컨텍스트 등록
   - 자바에서 {total:,} 번 점수 계산
   - user-service HTTP 동기 호출 2 회
 가 더 붙는다. 즉 실제 응답시간은 반드시 이 값보다 크다.

 그리고 이건 '동시 요청 1개'일 때다. 부하가 걸리면 CPU 경합으로 늘어난다.
 회차 1 에서 19ms 쿼리가 200 RPS 에서 500ms 가 됐다.
""")


if __name__ == "__main__":
    main()
