#!/usr/bin/env python3
"""
코매칭 부하 테스트용 시드 데이터 생성기.

표준 라이브러리만 사용한다 (pip install 불필요).
MySQL 의 LOAD DATA INFILE 로 밀어넣을 TSV 파일들을 out/ 에 만든다.

== 왜 TSV 인가 ==
LOAD DATA 의 기본 구분자가 탭이고, 우리 데이터에는 탭이 없어서
따옴표 이스케이프를 신경 쓸 필요가 없다. CSV 로 하면 쉼표가 들어간 값에서 깨진다.

== 왜 "고정 시드 셔플" 인가 ==
`i % 16` 같은 나머지 연산으로 속성을 배분하면 계수끼리 약수 관계가 생길 때
속성 간에 가짜 상관이 생긴다. 예를 들어 gender = i%2, major = (i*7)%20 이면
20 이 짝수라서 성별과 학과가 붙어버린다. 그러면 "남자는 특정 학과에 몰려 있다"는
데이터 인공물이 생기고, 매칭 후보 수가 성별에 따라 달라져 측정이 오염된다.

각 속성마다 정확한 개수의 리스트를 만들고 서로 다른 시드로 독립 셔플하면
 (1) 주변 분포가 정확히 균일하고
 (2) 속성 간 상관이 없고
 (3) 시드가 고정이라 재현 가능하다.

== 사용법 ==
    python3 generate_seed.py                      # 기본 10만 명
    python3 generate_seed.py --count 10000        # 스모크 테스트용 1만 명
    python3 generate_seed.py --help
"""

import argparse
import csv
import itertools
import random
import sys
from collections import Counter
from datetime import date
from pathlib import Path

# ---------------------------------------------------------------------------
# 도메인 값. 전부 실제 DB enum 에서 가져온 것이다.
#   DESCRIBE comatching_user.profile;
#   DESCRIBE comatching_matching.matching_candidate;
# 로 확인했다.
# ---------------------------------------------------------------------------

GENDERS = ["MALE", "FEMALE"]

# MatchingProcessor 의 MAX_ALLOWED_AGE 가 27 이라 그 아래로 잡는다.
AGES = [20, 21, 22, 23, 24, 25, 26]

MBTIS = [
    "ISTJ", "ISFJ", "INFJ", "INTJ",
    "ISTP", "ISFP", "INFP", "INTP",
    "ESTP", "ESFP", "ENFP", "ENTP",
    "ESTJ", "ESFJ", "ENFJ", "ENTJ",
]

CONTACT_FREQUENCIES = ["FREQUENT", "NORMAL", "RARE"]

HOBBY_CATEGORIES = ["CULTURE", "DAILY", "GAME", "LEISURE", "MUSIC", "SPORTS"]

# 취미는 '취미 하나당 한 행'이라 같은 카테고리가 여러 번 나올 수 있다.
# 게임 취미가 둘이면 게임에 더 가깝다고 보고 점수를 더 준다(10/15/20).
# combinations 는 중복 없는 조합만 만들어서 15·20 점 분기에 도달하는 데이터가
# 한 건도 생기지 않았다. 중복을 허용해야 점수 분포가 실제와 비슷해진다.
HOBBY_COMBOS = [list(c) for c in itertools.combinations_with_replacement(HOBBY_CATEGORIES, 3)]

# 학과는 19 개(소수). 20 처럼 짝수를 쓰면 셔플 전 단계에서 성별과 얽힐 여지가 있고,
# 소수면 어떤 배분 방식을 쓰더라도 주기가 겹치지 않는다.
MAJORS = [
    "컴퓨터정보공학부", "미디어기술콘텐츠학과", "경영학과", "경제학과", "국어국문학과",
    "영어영문학과", "심리학과", "사회학과", "법학과", "행정학과",
    "화학과", "생명과학과", "수학과", "물리학과", "의생명과학과",
    "간호학과", "약학과", "음악과", "디지털미디어학과",
]

TAGS = [
    "AFFECTIONATE", "ARTISTIC", "BRIGHT", "CALM", "CARING", "CREATIVE",
    "DETAIL_ORIENTED", "EASYGOING", "EXTROVERT", "FASHIONABLE", "GAMING",
    "GOOD_COOK", "GOOD_LISTENER", "GYM", "INTROVERT", "LOGICAL", "LOYAL",
    "MUSICAL", "ORGANIZED", "PASSIONATE", "PHOTOGRAPHY", "POSITIVE",
    "RUNNING", "SHY", "SPONTANEOUS", "SPORTS", "STRAIGHTFORWARD",
    "SWIMMING", "TALKATIVE", "WITTY", "YOGA",
]

UNIVERSITY = "가톨릭대학교"

# 시드 계정은 JWT 를 직접 발급해서 쓰므로 비밀번호 로그인은 하지 않는다.
# 실제 BCrypt 해시를 넣지 않는 이유: 잘못된 해시를 넣으면 로그인이 조용히 실패해서
# 원인을 찾기 어렵다. 차라리 명시적으로 못 쓰게 막아두는 편이 낫다.
DUMMY_PASSWORD = "$2a$10$SEEDONLYNOLOGINSEEDONLYNOLOGINSEEDONLYNOLOGINSEEDONLYNO"

# 각 속성마다 다른 시드를 쓴다. 같은 시드를 쓰면 셔플 결과가 같아져서 상관이 생긴다.
SEEDS = {
    "gender": 11,
    "age": 22,
    "mbti": 33,
    "contact": 44,
    "major": 55,
    "hobby": 66,
    "tag": 77,
}


def exact_uniform(values, n, seed):
    """
    values 를 정확히 균등하게 n 개 만들어 셔플한다.

    n 이 len(values) 로 나누어떨어지지 않으면 앞쪽 값들이 1 개씩 더 나온다.
    (예: 100000 / 7 = 14285.7 → 앞 5 개 값이 14286 개, 나머지가 14285 개)
    최대 편차가 1 이므로 균일하다고 봐도 된다.
    """
    q, r = divmod(n, len(values))
    out = []
    for i, v in enumerate(values):
        out.extend([v] * (q + (1 if i < r else 0)))
    random.Random(seed).shuffle(out)
    return out


def birth_date_for_age(korean_age, today=None):
    """
    한국 나이 -> 생년월일.

    한국 나이 = 올해 - 태어난 해 + 1  이므로
    태어난 해 = 올해 - 한국 나이 + 1.

    주의: matching_candidate.age 는 이 값을 다시 계산하지 않고 우리가 직접 채운다.
    KoreanAge 구현이 이 공식과 다르더라도 후보 테이블은 의도한 나이를 갖게 된다.
    (매칭 쿼리가 실제로 보는 건 candidate.age 컬럼이다)
    """
    today = today or date.today()
    year = today.year - korean_age + 1
    # 월/일을 분산시키면 생일 경계 케이스가 섞여 들어와 노이즈가 된다.
    # 측정 재현성을 위해 연중 고정일로 통일한다.
    return date(year, 6, 15).isoformat()


def write_tsv(path, rows):
    """
    탭 구분 파일로 쓴다.

    csv 모듈을 쓰지 않는 이유: QUOTE_NONE + escapechar 조합에서 백슬래시가
    다시 이스케이프되어 NULL 표기인 \\N 이 \\\\N 으로 나간다. 그러면 MySQL 이
    NULL 이 아니라 문자열 "\\N" 으로 읽는다.
    우리 데이터에는 탭도 개행도 없으므로 그냥 join 하는 게 정확하다.
    """
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for row in rows:
            f.write("\t".join(str(c) for c in row))
            f.write("\n")
    return len(rows)


def main():
    p = argparse.ArgumentParser(description="코매칭 부하 테스트 시드 생성기")
    p.add_argument("--count", type=int, default=100_000,
                   help="생성할 회원 수 (기본 100000)")
    p.add_argument("--start-id", type=int, default=1_000_001,
                   help="member_id 시작값. 실계정과 겹치지 않게 크게 잡는다 (기본 1000001)")
    p.add_argument("--vu-accounts", type=int, default=500,
                   help="매칭 시나리오에서 쓸 VU 계정 수. 이 계정들에만 아이템을 넣는다 (기본 500)")
    p.add_argument("--matching-tickets", type=int, default=2000,
                   help="VU 계정당 매칭권 수량 (기본 2000)")
    p.add_argument("--option-tickets", type=int, default=6000,
                   help="VU 계정당 옵션권 수량 (기본 6000)")
    p.add_argument("--out", default=str(Path(__file__).parent / "out"),
                   help="출력 디렉터리")
    args = p.parse_args()

    n = args.count
    base = args.start_id
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    if args.vu_accounts > n:
        print(f"[!] --vu-accounts({args.vu_accounts}) 가 --count({n}) 보다 큽니다.", file=sys.stderr)
        return 1

    print(f"시드 생성 시작: {n:,} 명, member_id {base:,} ~ {base + n - 1:,}")

    # --- 속성 배분 (각각 독립 셔플) --------------------------------------
    genders = exact_uniform(GENDERS, n, SEEDS["gender"])
    ages = exact_uniform(AGES, n, SEEDS["age"])
    mbtis = exact_uniform(MBTIS, n, SEEDS["mbti"])
    contacts = exact_uniform(CONTACT_FREQUENCIES, n, SEEDS["contact"])
    majors = exact_uniform(MAJORS, n, SEEDS["major"])
    hobby_idx = exact_uniform(list(range(len(HOBBY_COMBOS))), n, SEEDS["hobby"])

    # 태그는 1 인당 3 개. 태그 슬롯 전체(n*3)를 균등 배분한 뒤 3 개씩 끊는다.
    tag_pool = exact_uniform(TAGS, n * 3, SEEDS["tag"])

    # --- 행 만들기 --------------------------------------------------------
    members, profiles, hobbies, tags = [], [], [], []
    candidates, cand_hobbies, items, index = [], [], [], []

    for i in range(n):
        mid = base + i
        pid = mid  # profile_id 를 member_id 와 1:1 로 맞춘다 (조인 디버깅이 쉬워진다)
        email = f"perf{mid}@loadtest.local"
        nickname = f"perf{mid}"
        gender, age, mbti = genders[i], ages[i], mbtis[i]
        contact, major = contacts[i], majors[i]
        combo = HOBBY_COMBOS[hobby_idx[i]]

        # comatching_user.members
        members.append([mid, email, DUMMY_PASSWORD, f"부하{mid}",
                        r"\N", "ROLE_USER", "LOCAL", "ACTIVE"])

        # comatching_user.profile
        profiles.append([pid, mid, birth_date_for_age(age), 0, r"\N", major, mbti,
                         nickname, r"\N", f"insta{mid}", r"\N", UNIVERSITY,
                         contact, gender, "INSTAGRAM"])

        # comatching_user.profile_hobby  (1 인 3 개)
        for c in combo:
            hobbies.append([pid, f"{c}_HOBBY", c])

        # comatching_user.profile_tag  (1 인 3 개)
        for t in tag_pool[i * 3:(i + 1) * 3]:
            tags.append([pid, t])

        # comatching_matching.matching_candidate
        # age 를 직접 채우는 게 핵심이다. 이 테이블은 Kafka profile-updates 이벤트로만
        # 채워지는 구조라, SQL 로 직접 넣으면 age 가 비어 매칭이 전부 실패한다.
        candidates.append([mid, age, contact, gender, major, mbti, pid])

        for c in combo:
            cand_hobbies.append([mid, c])

        # comatching_item.item  (VU 계정에만)
        if i < args.vu_accounts:
            items.append([args.matching_tickets, "2099-12-31 00:00:00", mid, "MATCHING_TICKET"])
            items.append([args.option_tickets, "2099-12-31 00:00:00", mid, "OPTION_TICKET"])
            index.append([mid, email, nickname])

    # --- 파일로 쓰기 ------------------------------------------------------
    written = {
        "members.tsv": write_tsv(out / "members.tsv", members),
        "profile.tsv": write_tsv(out / "profile.tsv", profiles),
        "profile_hobby.tsv": write_tsv(out / "profile_hobby.tsv", hobbies),
        "profile_tag.tsv": write_tsv(out / "profile_tag.tsv", tags),
        "matching_candidate.tsv": write_tsv(out / "matching_candidate.tsv", candidates),
        "candidate_hobby_categories.tsv": write_tsv(out / "candidate_hobby_categories.tsv", cand_hobbies),
        "item.tsv": write_tsv(out / "item.tsv", items),
    }

    # VU 계정 목록. generate_tokens.py 가 이 파일을 읽어 JWT 를 만든다.
    with open(out / "vu_accounts.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["memberId", "email", "nickname"])
        w.writerows(index)

    # --- 분포 리포트 ------------------------------------------------------
    # 균일한지 눈으로 확인하는 용도. 여기가 틀어져 있으면 측정 결과를 믿을 수 없다.
    print("\n생성 파일")
    for name, cnt in written.items():
        print(f"  {name:<34} {cnt:>9,} 행")
    print(f"  {'vu_accounts.csv':<34} {len(index):>9,} 행")

    print("\n분포 확인 (편차가 1 이하여야 정상)")

    def report(label, counter):
        vals = sorted(counter.values())
        print(f"  {label:<20} 종류 {len(counter):>3}개  "
              f"최소 {vals[0]:,}  최대 {vals[-1]:,}  편차 {vals[-1] - vals[0]}")

    report("gender", Counter(genders))
    report("age", Counter(ages))
    report("mbti", Counter(mbtis))
    report("contact_frequency", Counter(contacts))
    report("major", Counter(majors))
    report("hobby_combo", Counter(hobby_idx))
    report("tag", Counter(tag_pool))

    # 성별 x 학과 교차표의 편차. 상관이 생겼는지 보는 지표다.
    cross = Counter(zip(genders, majors))
    cvals = sorted(cross.values())
    print(f"  {'gender x major':<20} 칸 {len(cross):>3}개  "
          f"최소 {cvals[0]:,}  최대 {cvals[-1]:,}  편차 {cvals[-1] - cvals[0]}")
    print("    (교차 편차가 크면 성별과 학과가 상관됐다는 뜻 — 후보 풀이 성별에 따라 달라진다)")

    print(f"\n출력 위치: {out}")
    print("다음: ./load_seed.sh")
    return 0


if __name__ == "__main__":
    sys.exit(main())
