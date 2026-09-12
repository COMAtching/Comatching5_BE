#!/usr/bin/env bash
# ============================================================================
# 생성된 TSV 를 RDS 에 적재한다. EC2 호스트에서 실행한다.
#
# 로컬용 load_seed.sh 와 갈라놓은 이유 - 그쪽의 두 전제가 RDS 에서 깨진다:
#   - docker exec comatching-mysql        RDS 는 컨테이너가 아니다
#   - 서버사이드 LOAD DATA INFILE          RDS 는 DB 서버 파일시스템에 파일을
#                                          놓을 수 없다 (secure_file_priv)
# 대신 클라이언트가 파일을 읽어 보내는 LOAD DATA LOCAL INFILE 을 쓴다.
#
# 선결 조건:
#   - RDS 파라미터 그룹에 local_infile=1 (기본 0. 콘솔에서 바꾸고 적용 대기)
#   - EC2 에 mysql 클라이언트  (dnf install mariadb105 또는 mysql)
#   - 시드 TSV: 로컬에서 generate_seed.py 로 만들어 scp 로 올린다
#
# 사용법:
#   RDS_ENDPOINT=... RDS_USERNAME=... RDS_PASSWORD=... ./load_seed_rds.sh
#   환경변수 이름은 .env.prod 와 같으므로 이렇게 부를 수도 있다:
#   set -a; . ~/comatching/.env.prod; set +a; ./load_seed_rds.sh
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

: "${RDS_ENDPOINT:?RDS_ENDPOINT 가 필요하다 (.env.prod 참고)}"
: "${RDS_USERNAME:?RDS_USERNAME 이 필요하다}"
: "${RDS_PASSWORD:?RDS_PASSWORD 가 필요하다}"
START_ID="${START_ID:-1000001}"
OUT_DIR="${OUT_DIR:-./out}"

mysql_exec() {
  # 비밀번호를 -p 인자로 주면 ps 에서 다른 사용자에게 보인다. 로컬 스크립트는
  # 일회용 개발 비밀번호라 넘어갔지만 이쪽은 운영 RDS 자격증명이다.
  MYSQL_PWD="$RDS_PASSWORD" mysql -h "$RDS_ENDPOINT" -u "$RDS_USERNAME" \
    --local-infile=1 --default-character-set=utf8mb4 "$@"
}

# ---------- 0. 사전 점검 ----------
if [ ! -f "$OUT_DIR/members.tsv" ]; then
  echo "❌ $OUT_DIR/members.tsv 가 없습니다. 로컬에서 generate_seed.py 를 돌려 scp 로 올리세요."
  exit 1
fi

# 서버가 local_infile 을 거부하면 여기서 일찍 죽는 편이 낫다.
LI=$(mysql_exec -N -B -e "SELECT @@GLOBAL.local_infile;")
if [ "$LI" != "1" ]; then
  echo "❌ RDS 의 local_infile 이 꺼져 있습니다 ($LI). 파라미터 그룹에서 local_infile=1 로 바꾸세요."
  exit 1
fi
echo "✅ RDS $RDS_ENDPOINT — local_infile=1"

# ---------- 1. 기존 시드 삭제 ----------
# 재실행 가능해야 한다. generate_seed.py 가 profile_id 를 member_id 와 1:1 로
# 맞춰두므로 조인 없이 ID 범위로 지운다.
echo "🧹 기존 시드 삭제 (member_id >= $START_ID)..."
mysql_exec <<SQL
SET foreign_key_checks = 0;
DELETE FROM comatching_user.profile_hobby WHERE profile_id >= $START_ID;
DELETE FROM comatching_user.profile_tag   WHERE profile_id >= $START_ID;
DELETE FROM comatching_user.profile       WHERE member_id  >= $START_ID;
DELETE FROM comatching_user.members       WHERE member_id  >= $START_ID;
DELETE FROM comatching_matching.candidate_hobby_categories WHERE member_id >= $START_ID;
DELETE FROM comatching_matching.matching_candidate         WHERE member_id >= $START_ID;
DELETE FROM comatching_item.item                           WHERE member_id >= $START_ID;
SET foreign_key_checks = 1;
SQL

# ---------- 2. 적재 ----------
# unique_checks / foreign_key_checks 를 끄면 InnoDB 가 행마다 검증하지 않아
# 벌크 적재가 크게 빨라진다. 시드는 이미 유일성이 보장돼 있다.
echo "⬇️  적재 중..."
time mysql_exec <<SQL
SET autocommit = 0;
SET unique_checks = 0;
SET foreign_key_checks = 0;

LOAD DATA LOCAL INFILE '$OUT_DIR/members.tsv'
  INTO TABLE comatching_user.members
  CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n'
  (member_id, email, password, real_name, social_id, role, social_type, status);

LOAD DATA LOCAL INFILE '$OUT_DIR/profile.tsv'
  INTO TABLE comatching_user.profile
  CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n'
  (profile_id, member_id, birth_date, point, intro, major, mbti, nickname,
   profile_image_url, social_account_id, song, university,
   contact_frequency, gender, social_account_type)
  SET is_matchable = 1;

LOAD DATA LOCAL INFILE '$OUT_DIR/profile_hobby.tsv'
  INTO TABLE comatching_user.profile_hobby
  CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n'
  (profile_id, name, category);

LOAD DATA LOCAL INFILE '$OUT_DIR/profile_tag.tsv'
  INTO TABLE comatching_user.profile_tag
  CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n'
  (profile_id, tag);

LOAD DATA LOCAL INFILE '$OUT_DIR/matching_candidate.tsv'
  INTO TABLE comatching_matching.matching_candidate
  CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n'
  (member_id, age, contact_frequency, gender, major, mbti, profile_id)
  SET is_matchable = 1;

LOAD DATA LOCAL INFILE '$OUT_DIR/candidate_hobby_categories.tsv'
  INTO TABLE comatching_matching.candidate_hobby_categories
  CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n'
  (member_id, hobby_categories);

LOAD DATA LOCAL INFILE '$OUT_DIR/item.tsv'
  INTO TABLE comatching_item.item
  CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n'
  (quantity, expired_at, member_id, item_type);

COMMIT;
SET unique_checks = 1;
SET foreign_key_checks = 1;
SQL

# ---------- 3. 검증 ----------
echo "✅ 적재 결과"
mysql_exec -t <<SQL
SELECT 'members'               AS tbl, COUNT(*) AS rows_ FROM comatching_user.members WHERE member_id >= $START_ID
UNION ALL SELECT 'profile',            COUNT(*) FROM comatching_user.profile          WHERE member_id >= $START_ID
UNION ALL SELECT 'matching_candidate', COUNT(*) FROM comatching_matching.matching_candidate WHERE member_id >= $START_ID
UNION ALL SELECT 'item',               COUNT(*) FROM comatching_item.item             WHERE member_id >= $START_ID;
SQL

echo "🔎 age NULL 검사 (0 이어야 정상 — NULL 이면 매칭이 전부 실패한다)"
mysql_exec -t -e "SELECT COUNT(*) AS age_is_null FROM comatching_matching.matching_candidate WHERE member_id >= $START_ID AND age IS NULL;"

echo "🏁 완료. 다음: 운영 시크릿으로 토큰 재발급 (generate_tokens.py --env ~/comatching/.env.prod)"
