#!/usr/bin/env bash
# ============================================================================
# 운영 스모크 테스트 — 부하 전에 "문이 다 열리는지" 확인한다.
#
# 읽기 전용 GET 만 친다. 상태를 바꾸는 요청(가입·매칭·구매)은 없다.
#
# 사용법:
#   ./smoke.sh                                  # 공개 엔드포인트만
#   ACCESS_TOKEN=<jwt> ./smoke.sh               # 인증 엔드포인트까지
#   BASE_URL=http://localhost:8080 ./smoke.sh   # 로컬 대상
#
# 인증은 게이트웨이가 accessToken "쿠키"만 읽는다 (Authorization 헤더 아님).
# 토큰은 EC2 에서 tools/perf/tokens/generate_tokens.py 로 발급하거나
# 브라우저 로그인 후 개발자도구에서 복사한다.
# ============================================================================
set -uo pipefail

BASE_URL="${BASE_URL:-https://srv.comatching.site}"
ACCESS_TOKEN="${ACCESS_TOKEN:-}"
TIMEOUT="${TIMEOUT:-10}"

pass=0; fail=0
declare -a rows

check() { # <이름> <기대코드> <경로> [auth]
  local name="$1" expect="$2" path="$3" auth="${4:-}"
  local cookie=()
  [ "$auth" = "auth" ] && cookie=(-b "accessToken=$ACCESS_TOKEN")
  local out
  out=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time "$TIMEOUT" \
        "${cookie[@]}" "$BASE_URL$path" 2>/dev/null) || out="000 -"
  local code="${out%% *}" time="${out##* }"
  local mark="❌"
  if [ "$code" = "$expect" ]; then mark="✅"; pass=$((pass+1)); else fail=$((fail+1)); fi
  rows+=("$(printf '%s %-38s %s (기대 %s)  %ss' "$mark" "$name" "$code" "$expect" "$time")")
}

echo "════════════════════════════════════════════════════════"
echo " 스모크 대상: $BASE_URL"
echo "════════════════════════════════════════════════════════"

# ---------- 공개 (인증 불필요) ----------
check "참가자 수 조회"        200 "/api/auth/participants"
check "닉네임 중복 확인"      200 "/api/auth/signup/nickname/availability?nickname=smoke_test_probe"
check "취미 카테고리"         200 "/api/hobbies/categories"
check "프로필 태그"           200 "/api/profile/tags"

# ---------- 인증 경계 (토큰 없이 401 이 나와야 정상) ----------
check "매칭 히스토리(비인증)" 401 "/api/matching/history"
check "아이템 목록(비인증)"   401 "/api/items"

# ---------- 인증 (읽기 전용) ----------
if [ -n "$ACCESS_TOKEN" ]; then
  check "매칭 히스토리"       200 "/api/matching/history"       auth
  check "아이템 목록"         200 "/api/items"                  auth
  check "아이템 사용 이력"    200 "/api/items/history"          auth
  check "채팅방 목록"         200 "/api/chat/rooms"             auth
  check "채팅 안읽음 수"      200 "/api/chat/rooms/unread-count" auth
else
  echo "ℹ️  ACCESS_TOKEN 미설정 — 인증 엔드포인트 5개는 건너뜀"
fi

echo
for r in "${rows[@]}"; do echo "$r"; done
echo
echo "결과: ✅ $pass  ❌ $fail"
[ "$fail" -eq 0 ]
