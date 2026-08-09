#!/usr/bin/env bash
# ============================================================================
# 생성된 TSV 를 MySQL 컨테이너에 적재한다.
#
# == 왜 LOAD DATA INFILE 인가 ==
# INSERT 문을 수십만 개 날리면 파싱/트랜잭션 오버헤드로 몇 분씩 걸린다.
# LOAD DATA 는 서버가 파일을 직접 읽어서 벌크로 넣는다. 10만 행이면 수 초다.
#
# == 왜 LOCAL 이 아닌 서버 사이드인가 ==
# LOAD DATA *LOCAL* INFILE 은 서버와 클라이언트 양쪽에서 local_infile 을
# 켜야 하고, MySQL 8 은 기본이 꺼져 있다. 실패 지점이 둘 늘어난다.
# docker cp 로 컨테이너 안에 넣고 서버가 읽게 하면 그 설정이 필요 없다.
#
# == 재실행 가능하다 ==
# 적재 전에 START_ID 이상의 시드 행을 지운다. user/item 서비스가
# ddl-auto: create 라 재기동하면 스키마가 날아가므로, 이 스크립트는
# 언제 다시 돌려도 같은 상태가 되어야 한다.
#
# 사용법:
#   ./load_seed.sh                # 기본값으로 적재
#   START_ID=1000001 ./load_seed.sh
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

CONTAINER="${CONTAINER:-comatching-mysql}"
MYSQL_PW="${MYSQL_ROOT_PASSWORD:-comatching12!@}"
START_ID="${START_ID:-1000001}"
OUT_DIR="${OUT_DIR:-./out}"
DEST="/var/lib/mysql-files"

mysql_exec() {
  docker exec -i "$CONTAINER" mysql -uroot -p"$MYSQL_PW" --default-character-set=utf8mb4 "$@"
}

# ---------- 0. 사전 점검 ----------
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
  echo "❌ 컨테이너 '$CONTAINER' 가 실행 중이 아닙니다. ./run-local.sh --infra-only 로 먼저 띄우세요."
  exit 1
fi

if [ ! -f "$OUT_DIR/members.tsv" ]; then
  echo "❌ $OUT_DIR/members.tsv 가 없습니다. 먼저 python3 generate_seed.py 를 실행하세요."
  exit 1
fi

# secure_file_priv 가 우리가 쓰려는 경로와 맞는지 확인한다.
# 이 값이 다르면 LOAD DATA 가 'The MySQL server is running with the
# --secure-file-priv option' 로 거부한다.
SFP=$(mysql_exec -N -B -e "SELECT @@secure_file_priv;" 2>/dev/null | tr -d '\r')
echo "secure_file_priv = ${SFP:-<empty>}"
if [ -n "$SFP" ] && [ "$SFP" != "NULL" ]; then
  DEST="${SFP%/}"
fi
echo "적재 경로       = $DEST"

# ---------- 1. 파일 복사 ----------
echo ""
echo "📦 TSV 를 컨테이너로 복사..."
for f in members profile profile_hobby profile_tag matching_candidate candidate_hobby_categories item; do
  src="$OUT_DIR/$f.tsv"
  [ -f "$src" ] || { echo "  ⚠️  $src 없음 — 건너뜀"; continue; }
  docker cp "$src" "$CONTAINER:$DEST/$f.tsv"
  printf "  %-30s %s\n" "$f.tsv" "$(wc -l < "$src" | tr -d ' ') 행"
done
# docker cp 는 root 소유로 넣는다. mysqld 는 mysql 유저로 돌기 때문에 읽을 수 있게 바꿔준다.
docker exec -u root "$CONTAINER" sh -c "chown mysql:mysql $DEST/*.tsv 2>/dev/null || true"

# ---------- 2. 기존 시드 삭제 ----------
echo ""
echo "🧹 기존 시드 데이터 삭제 (member_id >= $START_ID)..."
# 다중 테이블 DELETE(DELETE ph FROM ... JOIN ...) 는 기본 DB 가 선택돼 있지 않으면
# DELETE 절의 별칭을 해석하지 못하고 ERROR 1046 을 낸다.
# generate_seed.py 가 profile_id 를 member_id 와 1:1 로 맞춰두므로 조인이 필요 없다.
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

# ---------- 3. 적재 ----------
# unique_checks / foreign_key_checks 를 끄면 InnoDB 가 행마다 검증하지 않아
# 벌크 적재가 크게 빨라진다. 우리가 만든 데이터는 이미 유일성이 보장돼 있다.
echo ""
echo "⬇️  적재 중..."
time mysql_exec <<SQL
SET autocommit = 0;
SET unique_checks = 0;
SET foreign_key_checks = 0;

LOAD DATA INFILE '$DEST/members.tsv'
  INTO TABLE comatching_user.members
  CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n'
  (member_id, email, password, real_name, social_id, role, social_type, status);

LOAD DATA INFILE '$DEST/profile.tsv'
  INTO TABLE comatching_user.profile
  CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n'
  (profile_id, member_id, birth_date, point, intro, major, mbti, nickname,
   profile_image_url, social_account_id, song, university,
   contact_frequency, gender, social_account_type)
  SET is_matchable = 1;

LOAD DATA INFILE '$DEST/profile_hobby.tsv'
  INTO TABLE comatching_user.profile_hobby
  CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n'
  (profile_id, name, category);

LOAD DATA INFILE '$DEST/profile_tag.tsv'
  INTO TABLE comatching_user.profile_tag
  CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n'
  (profile_id, tag);

LOAD DATA INFILE '$DEST/matching_candidate.tsv'
  INTO TABLE comatching_matching.matching_candidate
  CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n'
  (member_id, age, contact_frequency, gender, major, mbti, profile_id)
  SET is_matchable = 1;

LOAD DATA INFILE '$DEST/candidate_hobby_categories.tsv'
  INTO TABLE comatching_matching.candidate_hobby_categories
  CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n'
  (member_id, hobby_categories);

LOAD DATA INFILE '$DEST/item.tsv'
  INTO TABLE comatching_item.item
  CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n'
  (quantity, expired_at, member_id, item_type);

COMMIT;
SET unique_checks = 1;
SET foreign_key_checks = 1;
SQL

# ---------- 4. 검증 ----------
echo ""
echo "✅ 적재 결과"
mysql_exec -t <<SQL
SELECT 'members'                    AS tbl, COUNT(*) AS rows_ FROM comatching_user.members     WHERE member_id >= $START_ID
UNION ALL SELECT 'profile',                 COUNT(*) FROM comatching_user.profile              WHERE member_id >= $START_ID
UNION ALL SELECT 'profile_hobby',           COUNT(*) FROM comatching_user.profile_hobby   WHERE profile_id >= $START_ID
UNION ALL SELECT 'profile_tag',             COUNT(*) FROM comatching_user.profile_tag     WHERE profile_id >= $START_ID
UNION ALL SELECT 'matching_candidate',      COUNT(*) FROM comatching_matching.matching_candidate WHERE member_id >= $START_ID
UNION ALL SELECT 'candidate_hobby_cat',     COUNT(*) FROM comatching_matching.candidate_hobby_categories WHERE member_id >= $START_ID
UNION ALL SELECT 'item',                    COUNT(*) FROM comatching_item.item                WHERE member_id >= $START_ID;
SQL

echo ""
echo "🔎 age NULL 검사 (0 이어야 정상 — NULL 이면 매칭이 전부 실패한다)"
mysql_exec -t -e "SELECT COUNT(*) AS age_is_null FROM comatching_matching.matching_candidate WHERE member_id >= $START_ID AND age IS NULL;"

echo "🔎 성별 분포 (5:5 여야 정상)"
mysql_exec -t -e "SELECT gender, COUNT(*) AS cnt FROM comatching_matching.matching_candidate WHERE member_id >= $START_ID GROUP BY gender;"

echo "🔎 participants 카운트 (부하 대상 쿼리와 동일한 조건)"
mysql_exec -t -e "SELECT COUNT(*) AS active_users FROM comatching_user.members WHERE role='ROLE_USER' AND status='ACTIVE';"

# 컨테이너 안 임시 파일 정리
docker exec -u root "$CONTAINER" sh -c "rm -f $DEST/*.tsv" 2>/dev/null || true

echo ""
echo "🏁 완료. 다음: cd ../tokens && python3 generate_tokens.py"
