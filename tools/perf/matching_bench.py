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
import random
import subprocess
import sys
import time

CONTAINER = os.environ.get("CONTAINER", "comatching-mysql")
MYSQL_PW = os.environ.get("MYSQL_ROOT_PASSWORD", "comatching12!@")
DB = "comatching_matching"
HOBBY_BATCH = 100          # MatchingCandidate.hobbyCategories 의 @BatchSize(size = 100)
AGE_OPERATOR = {"EQUAL": "=", "OLDER": ">", "YOUNGER": "<"}
# MatchingCandidate.RANDOM_KEY_START_BOUND 와 같은 값
RANDOM_KEY_START_BOUND = 900_000_000


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


def score_terms(args, hobby_count_expr):
    """
    점수식. old/new/sample 이 같은 규칙을 써야 비교가 성립한다.
    hobby_count_expr 만 모드별로 다르다(파생 테이블의 cnt vs 집계 COUNT).
    """
    mbti = "\n        + ".join(
        f"CASE WHEN LOCATE('{c}', c.mbti) > 0 THEN 10 ELSE 0 END" for c in args.mbti
    )
    return (f"{mbti}"
            f"\n        + CASE WHEN {hobby_count_expr} >= 3 THEN 20"
            f"\n               WHEN {hobby_count_expr} = 2 THEN 15"
            f"\n               WHEN {hobby_count_expr} = 1 THEN 10"
            f"\n               ELSE 0 END"
            f"\n        + CASE WHEN c.age {AGE_OPERATOR[args.age_option]} {args.my_age} THEN 20 ELSE 0 END"
            f"\n        + CASE WHEN c.contact_frequency = '{args.contact}' THEN 10 ELSE 0 END")


def sample_candidate_sql(args, random_start):
    """
    개선 B(표본 추출)가 만들어내는 SQL.

    (gender, is_matchable, random_key) 인덱스에서 무작위 지점으로 점프해
    연속 N 건을 읽는다. random_key 는 삽입 시 한 번 정해지는 무작위 정수라
    '무작위 공간에서의 연속 구간 = 무작위 표본'이 된다.

    걸러내기를 gender + is_matchable 만 거는 건 old/new 와 후보 모집단을
    맞추기 위해서다. 조건을 더 걸면 후보가 줄어 당연히 빨라지는데 그건 개선이 아니다.
    """
    return f"""SELECT c.*
   FROM (SELECT mc.member_id
           FROM matching_candidate mc
          WHERE mc.gender = '{args.gender}'
            AND mc.is_matchable = 1
            AND mc.random_key >= {random_start}
          ORDER BY mc.random_key
          LIMIT {args.sample_size}) s
   JOIN matching_candidate c ON c.member_id = s.member_id
   LEFT JOIN candidate_hobby_categories h
          ON h.member_id = c.member_id AND h.hobby_categories = '{args.hobby}'
  GROUP BY c.member_id
  ORDER BY ({score_terms(args, "COUNT(h.member_id)")}) DESC, RAND()
  LIMIT 1;"""


def best_candidate_sql(args):
    """
    개선 후 MatchingCandidateRepositoryImpl.findBestCandidate 가 만들어내는 SQL 과 같은 모양.

    걸러내기 조건을 gender + is_matchable 만 거는 건 의도한 것이다.
    old 모드와 후보 모집단을 똑같이 맞춰야 '쿼리 모양만 바꿨을 때의 차이'가 나온다.
    필수 조건을 더 걸면 후보가 줄어서 당연히 빨라지는데, 그건 개선이 아니라 조건 변경이다.

    점수식은 앱과 동일하다 — MBTI 글자당 10, 취미 개수별 10/15/20, 나이 20, 연락빈도 10.
    벤치는 항상 점수 항이 있는 요청을 재현하므로 정렬식이 비는 경우는 없다.
    (앱은 옵션이 하나도 없으면 ORDER BY 에서 점수를 통째로 뺀다. MySQL 이
     ORDER BY 의 정수 리터럴을 컬럼 순번으로 읽어서 "0" 을 넣으면 죽기 때문인데,
     괄호로 감싸도 막히지 않는다.)
    """
    mbti_terms = "\n        + ".join(
        f"CASE WHEN LOCATE('{c}', c.mbti) > 0 THEN 10 ELSE 0 END" for c in args.mbti
    )
    return f"""SELECT c.* FROM matching_candidate c
 LEFT JOIN (SELECT member_id, COUNT(*) AS cnt
              FROM candidate_hobby_categories
             WHERE hobby_categories = '{args.hobby}'
             GROUP BY member_id) h ON h.member_id = c.member_id
 WHERE c.gender = '{args.gender}'
   AND c.is_matchable = 1
 ORDER BY ({mbti_terms}
        + CASE WHEN COALESCE(h.cnt, 0) >= 3 THEN 20
               WHEN COALESCE(h.cnt, 0) = 2 THEN 15
               WHEN COALESCE(h.cnt, 0) = 1 THEN 10
               ELSE 0 END
        + CASE WHEN c.age {AGE_OPERATOR[args.age_option]} {args.my_age} THEN 20 ELSE 0 END
        + CASE WHEN c.contact_frequency = '{args.contact}' THEN 10 ELSE 0 END) DESC, RAND()
 LIMIT 1;"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", default="old", choices=["old", "new", "sample"],
                    help="old=개선 전(페이지100+취미N+1 500), new=A(단일 쿼리 전수조사), "
                         "sample=B(무작위 표본)")
    ap.add_argument("--gender", default="FEMALE", choices=["FEMALE", "MALE"],
                    help="탐색 대상 성별. 앱은 '내 성별의 반대'를 넣는다")
    ap.add_argument("--page-size", type=int, default=500,
                    help="[old] MatchingProcessor.MAX_CANDIDATE_FETCH_SIZE 와 같은 값")
    ap.add_argument("--no-hobby", action="store_true", help="[old] 취미 N+1 재현을 생략")
    ap.add_argument("--member-id", type=int, default=1000001,
                    help="제외 목록 조회에 쓸 회원 id")
    # --- [new] 점수식 파라미터 ---
    # 걸러내기 조건은 일부러 old 와 똑같이 gender + is_matchable 만 건다.
    # 후보 모집단이 같아야 '쿼리 모양만 바꿨을 때의 차이'를 잴 수 있다.
    ap.add_argument("--mbti", default="ENFP", help="[new] 점수용 MBTI 글자")
    ap.add_argument("--hobby", default="GAME", help="[new] 점수용 취미 카테고리")
    ap.add_argument("--my-age", type=int, default=24, help="[new] 내 나이")
    ap.add_argument("--age-option", default="EQUAL", choices=["EQUAL", "OLDER", "YOUNGER"])
    ap.add_argument("--contact", default="NORMAL", choices=["FREQUENT", "NORMAL", "RARE"])
    ap.add_argument("--sample-size", type=int, default=5000,
                    help="[sample] MatchingProcessor.SAMPLE_SIZE 와 같은 값")
    ap.add_argument("--repeat", type=int, default=1,
                    help="[new] 쿼리가 1 개뿐이라 편차가 크면 늘려서 평균을 본다")
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

    mode_desc = {"old": "개선 전 — 페이지 + 취미 N+1",
                 "new": "A — 단일 쿼리 전수조사",
                 "sample": "B — 무작위 표본"}[args.mode]
    print(f" 모드        : {args.mode}  ({mode_desc})")
    print(f" 대상 성별   : {args.gender}  (후보 {total:,} 명)")

    # ---------- 워밍업 ----------
    # 버퍼 풀이 비어 있으면 디스크 I/O 가 섞인다. 측정 대상 인덱스를 미리 데운다.
    print("\n▸ 버퍼 풀 워밍업...")
    mysql(f"SELECT COUNT(*) FROM matching_candidate WHERE {where};"
          "SELECT COUNT(*) FROM candidate_hobby_categories;")

    # ---------- 측정할 SQL 을 전부 만든다 ----------
    stmts = []

    # ① 제외 목록: WHERE member_id = ? OR partner_id = ?
    #    partner_id 단독 인덱스가 없어서 OR 가 인덱스를 못 탄다.
    #    두 모드 모두 이 쿼리는 그대로 나간다(개선 A 의 대상이 아니다).
    if args.mode == "sample":
        # B 에서 OR 를 UNION 으로 쪼갰다. 각 갈래가 자기 인덱스를 커버링으로 탄다.
        exclude_sql = (
            f"SELECT partner_id FROM matching_history WHERE member_id  = {args.member_id} "
            f"UNION "
            f"SELECT member_id  FROM matching_history WHERE partner_id = {args.member_id};")
    else:
        exclude_sql = (
            f"SELECT DISTINCT CASE WHEN m.member_id = {args.member_id} "
            f"THEN m.partner_id ELSE m.member_id END FROM matching_history m "
            f"WHERE m.member_id = {args.member_id} OR m.partner_id = {args.member_id};")

    if args.mode == "old":
        pages = (total + args.page_size - 1) // args.page_size
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

        stmts.append(exclude_sql)

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
    else:
        print(f" 점수 조건   : mbti={args.mbti} hobby={args.hobby} "
              f"age={args.age_option}({args.my_age}) contact={args.contact}")
        if args.mode == "sample":
            print(f" 표본 크기   : {args.sample_size:,}  (전체 {total:,} 명 중)")
        print(f" 반복        : {args.repeat} 회")
        stmts.append(exclude_sql)
        for _ in range(args.repeat):
            # 표본 시작점은 매 요청 새로 뽑는다. 앱과 같은 동작이다.
            stmts.append(sample_candidate_sql(args, random.randrange(RANDOM_KEY_START_BOUND))
                         if args.mode == "sample" else best_candidate_sql(args))

    # ---------- 실행 ----------
    print(f"▸ 쿼리 {len(stmts):,} 개 실행 중 (단일 세션)...")
    mysql("TRUNCATE performance_schema.events_statements_summary_by_digest;")

    t0 = time.perf_counter()
    mysql_discard("\n".join(stmts))
    wall_ms = (time.perf_counter() - t0) * 1000

    # ---------- 집계 ----------
    # timer_wait 단위는 피코초. /1e9 하면 ms.
    # DIGEST_TEXT 를 자르면 안 된다. 아래에서 테이블 이름으로 쿼리 종류를 구분하는데,
    # 60자로 자르면 candidate_hobby_categories / matching_history 가 잘려나가
    # 전부 '후보 페이지'로 오분류된다. 실제로 그렇게 찍혔었다.
    rows = mysql("""
        SELECT COUNT_STAR, ROUND(SUM_TIMER_WAIT/1e9, 2), ROUND(AVG_TIMER_WAIT/1e9, 3),
               SUM_ROWS_EXAMINED, REPLACE(DIGEST_TEXT, '\\n', ' ')
          FROM performance_schema.events_statements_summary_by_digest
         WHERE SCHEMA_NAME = 'comatching_matching'
           AND DIGEST_TEXT LIKE 'SELECT%'
           -- mysql CLI 가 접속할 때마다 스스로 날리는 핸드셰이크 쿼리다.
           -- 우리 코드와 무관한 측정 노이즈라 뺀다.
           AND DIGEST_TEXT NOT LIKE 'SELECT @@%'
         ORDER BY SUM_TIMER_WAIT DESC;
    """)

    # 같은 종류인데 다이제스트가 갈리는 경우가 있다. 첫 페이지만 keyset 조건
    # (member_id > ?)이 없어서 별도 다이제스트로 잡히는 게 대표적이다.
    # 그래서 종류별로 합산해서 보여준다.
    buckets = {}
    server_ms = 0.0
    nqueries = 0
    scanned = 0
    for line in rows.splitlines():
        if not line.strip():
            continue
        cnt, tot, avg, examined, text = line.split("\t")
        if "performance_schema" in text or "ROW_NUMBER" in text:
            continue

        if "matching_history" in text:
            label = "① 제외 목록"
        elif args.mode == "sample":
            label = f"② 최적 후보 (표본 {args.sample_size:,})"
        elif args.mode == "new":
            # new 모드의 후보 쿼리는 취미 파생 테이블을 안에 품고 있어
            # candidate_hobby 도 같이 잡힌다. 하나의 쿼리로 센다.
            label = "② 최적 후보 (단일 쿼리)"
        elif "candidate_hobby_categories" in text:
            label = "③ 취미 컬렉션 (N+1)"
        else:
            label = "② 후보 페이지"

        b = buckets.setdefault(label, [0, 0.0, 0])
        b[0] += int(cnt)
        b[1] += float(tot)
        b[2] += int(examined)
        server_ms += float(tot)
        nqueries += int(cnt)
        scanned += int(examined)

    print("\n" + "-" * 72)
    print(f"{'실행':>7} {'총 ms':>10} {'평균 ms':>9} {'스캔 행':>12}  쿼리")
    print("-" * 72)
    for label, (cnt, tot, examined) in sorted(buckets.items(), key=lambda kv: -kv[1][1]):
        print(f"{cnt:>7,} {tot:>10,.1f} {tot / cnt:>9.3f} {examined:>12,}  {label}")

    print("-" * 72)
    print(f"  SQL 서버시간 합계 : {server_ms:>9,.1f} ms   (쿼리 {nqueries:,} 회, 스캔 {scanned:,} 행)")
    print(f"  세션 벽시계 시간  : {wall_ms:>9,.1f} ms   (프로토콜 왕복 포함)")
    print("-" * 72)

    # ---------- 실행 계획 ----------
    print("\n▸ 실행 계획")
    if args.mode == "old":
        cols = "member_id, age, contact_frequency, gender, is_matchable, major, mbti, profile_id"
        plans = [("② 후보 페이지",
                  f"SELECT {cols} FROM matching_candidate WHERE {where} "
                  f"AND member_id > 1000001 ORDER BY member_id ASC LIMIT {args.page_size}")]
    elif args.mode == "sample":
        plans = [("② 최적 후보",
                  sample_candidate_sql(args, random.randrange(RANDOM_KEY_START_BOUND)).rstrip(";"))]
    else:
        plans = [("② 최적 후보", best_candidate_sql(args).rstrip(";"))]

    plans.append(("① 제외 목록",
                  f"SELECT DISTINCT CASE WHEN m.member_id = {args.member_id} THEN m.partner_id "
                  f"ELSE m.member_id END FROM matching_history m "
                  f"WHERE m.member_id = {args.member_id} OR m.partner_id = {args.member_id}"))

    # EXPLAIN 의 탭 구분 컬럼 순서 (12개):
    # 0 id / 1 select_type / 2 table / 3 partitions / 4 type / 5 possible_keys
    # 6 key / 7 key_len / 8 ref / 9 rows / 10 filtered / 11 Extra
    # partitions 가 끼어 있어서 한 칸씩 밀린다. 이걸 놓쳐서 type 자리에 NULL 이 찍혔었다.
    for name, q in plans:
        for line in mysql(f"EXPLAIN {q};").splitlines():
            if not line.strip():
                continue
            f = line.split("\t")
            print(f"   {name:<14} table={f[2] or '-':<22} type={f[4]:<8} "
                  f"key={f[6]:<26} rows={f[9]:>8}  {f[11]}")
            name = ""   # 같은 쿼리의 두 번째 줄부터는 이름을 비운다

    if args.mode in ("new", "sample"):
        shown = (sample_candidate_sql(args, random.randrange(RANDOM_KEY_START_BOUND))
                 if args.mode == "sample" else best_candidate_sql(args))
        print("\n▸ 실제 실행된 SQL (그대로 복사해서 EXPLAIN ANALYZE 를 돌릴 수 있다)")
        print("\n".join("   " + l for l in shown.splitlines()))

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
