#!/usr/bin/env python3
"""
JMeter 용 JWT accessToken 을 미리 발급해 CSV 로 만든다.

표준 라이브러리만 쓴다 (PyJWT 불필요). HS256 은 HMAC-SHA256 서명 하나라
hmac + hashlib 로 충분하다.

== 왜 로그인 API 를 안 쓰나 ==
계정 500 개에 로그인 API 를 태우면 BCrypt 검증(의도적으로 느린 해시)만
수십 초가 걸리고, 시드 계정은 어차피 더미 비밀번호라 로그인이 안 된다.
같은 비밀키로 직접 서명하면 즉시 끝난다.

== 클레임 구조는 추측이 아니다 ==
common-module/.../JwtUtil.java 와
gateway-service/.../AuthorizationHeaderFilter.java 를 읽고 맞춘 것이다.

  JwtUtil.java:36   Decoders.BASE64.decode(secret)  -> 비밀키를 Base64 디코드한 바이트로 서명
  JwtUtil.java:69   setSubject(memberId)            -> sub
  JwtUtil.java:75   setId(UUID)                     -> jti  ★ 필수
  JwtUtil.java:78~  claim("email"/"role"/"status"/"nickname")

  AuthorizationHeaderFilter.java:69  accessTokenDenylistService.isDenied(claims.getId())
      -> jti 가 없으면 denylist 조회가 깨져 토큰이 거부된다.
  AuthorizationHeaderFilter.java:85~ sub/email/role 을 X-Member-* 헤더로 주입

즉 401 이 나면 의심 순서는 (1) jti 누락 (2) Base64 디코드 누락 (3) 만료다.

== 사용법 ==
    python3 generate_tokens.py
    python3 generate_tokens.py --expire-days 30
"""

import argparse
import base64
import csv
import hashlib
import hmac
import json
import sys
import time
import uuid
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_ENV = REPO_ROOT / ".env"
DEFAULT_ACCOUNTS = Path(__file__).resolve().parents[1] / "seed" / "out" / "vu_accounts.csv"


def b64url(data: bytes) -> str:
    """JWT 는 패딩 없는 base64url 을 쓴다."""
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def read_env_value(env_path: Path, key: str) -> str:
    """
    .env 에서 값 하나만 읽는다.

    셸을 거치지 않으므로 따옴표가 있든 없든 동일하게 동작한다.
    (예전에 SMTP_PASSWORD 가 따옴표 없이 공백을 포함해 source 가 깨진 적이 있다)
    """
    if not env_path.exists():
        raise SystemExit(f"❌ {env_path} 가 없습니다.")
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        if k.strip() != key:
            continue
        v = v.strip()
        if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
            v = v[1:-1]
        return v
    raise SystemExit(f"❌ {env_path} 에 {key} 가 없습니다.")


def make_token(key: bytes, member_id: int, email: str, nickname: str,
               issued_at: int, expire_seconds: int) -> str:
    header = {"alg": "HS256"}
    payload = {
        "sub": str(member_id),
        "jti": str(uuid.uuid4()),      # ★ 없으면 게이트웨이 denylist 조회에서 막힌다
        "iat": issued_at,
        "exp": issued_at + expire_seconds,
        "email": email,
        "role": "ROLE_USER",
        "status": "ACTIVE",
        "nickname": nickname,
    }
    # separators 로 공백을 없앤다. JWT 는 바이트 단위로 서명하므로 표현이 정확해야 한다.
    segments = [
        b64url(json.dumps(header, separators=(",", ":")).encode()),
        b64url(json.dumps(payload, separators=(",", ":")).encode()),
    ]
    signing_input = ".".join(segments).encode("ascii")
    signature = hmac.new(key, signing_input, hashlib.sha256).digest()
    segments.append(b64url(signature))
    return ".".join(segments)


def main():
    p = argparse.ArgumentParser(description="JMeter 용 JWT 발급기")
    p.add_argument("--env", default=str(DEFAULT_ENV), help=".env 경로")
    p.add_argument("--accounts", default=str(DEFAULT_ACCOUNTS),
                   help="generate_seed.py 가 만든 vu_accounts.csv 경로")
    p.add_argument("--out", default=str(Path(__file__).parent / "tokens.csv"))
    p.add_argument("--expire-days", type=int, default=7,
                   help="만료까지 일수. 부하 도중 만료되면 401 이 섞여 측정이 오염된다 (기본 7)")
    p.add_argument("--with-header", action="store_true",
                   help="CSV 첫 줄에 헤더를 넣는다. JMeter CSV Data Set Config 는 헤더가 없는 편이 편하다")
    args = p.parse_args()

    secret_b64 = read_env_value(Path(args.env), "JWT_SECRET")
    try:
        key = base64.b64decode(secret_b64)
    except Exception as e:
        raise SystemExit(f"❌ JWT_SECRET 을 Base64 디코드하지 못했습니다: {e}")

    # HS256 은 최소 256 비트(32 바이트) 키를 요구한다.
    # jjwt 의 Keys.hmacShaKeyFor 도 32 바이트 미만이면 예외를 던진다.
    if len(key) < 32:
        raise SystemExit(
            f"❌ 디코드된 키가 {len(key)} 바이트입니다. HS256 은 32 바이트 이상이어야 합니다.\n"
            f"   JWT_SECRET 이 Base64 문자열이 맞는지 확인하세요."
        )

    accounts_path = Path(args.accounts)
    if not accounts_path.exists():
        raise SystemExit(
            f"❌ {accounts_path} 가 없습니다.\n"
            f"   먼저 tools/perf/seed/generate_seed.py 를 실행하세요."
        )

    with open(accounts_path, newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    if not rows:
        raise SystemExit("❌ vu_accounts.csv 가 비어 있습니다.")

    now = int(time.time())
    expire_seconds = args.expire_days * 86400

    out_path = Path(args.out)
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        if args.with_header:
            w.writerow(["memberId", "accessToken"])
        for r in rows:
            mid = int(r["memberId"])
            token = make_token(key, mid, r["email"], r["nickname"], now, expire_seconds)
            w.writerow([mid, token])

    print(f"✅ {len(rows):,} 개 토큰 생성 → {out_path}")
    print(f"   키 길이   : {len(key)} 바이트")
    print(f"   만료      : {args.expire_days} 일 후")
    print(f"   memberId  : {rows[0]['memberId']} ~ {rows[-1]['memberId']}")
    print()
    print("다음 — 토큰이 실제로 통하는지 확인 (반드시 하고 넘어갈 것):")
    print()
    print("  TOKEN=$(head -1 %s | cut -d, -f2)" % out_path)
    print("  curl -s -o /dev/null -w '%{http_code}\\n' \\")
    print("    -b \"accessToken=$TOKEN\" http://localhost:8080/api/matching/history")
    print()
    print("  200 이면 성공. 401 이면 jti 누락 / Base64 디코드 / 만료 순으로 의심.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
