# 로컬 통합 테스트 데이터

노트북에 설치된 MySQL용이다. Docker MySQL이나 RDS 적재 스크립트를 사용하지 않는다.
생성기, 검증기, `plan`, `--apply` 없는 `load`/`clean`은 DB에 접속하지 않는다.

2026-11-18 파일 생성·오프라인 검증 후, 사용자의 적재 요청에 따라 **로컬 MySQL과 MongoDB
적재 및 커밋 후 검증을 완료**했다. 기존 회원 1명의 데이터는 그대로 보존했다.
로그인/업무 API 호출, Kafka 연동 테스트 및 부하 테스트는 수행하지 않았다.
실제 적재 결과는 `out/local-100k-20261118.load-report.json`에 기록한다.

## 준비된 파일

백엔드 저장소에서 다음 폴더로 이동한다.

```bash
cd tools/perf/seed/local
```

- `out/local-100k-20261118/`: 회원 100,000명 기본 데이터
- `out/no-candidates-20261118/`: 정상 매칭 요청으로 후보 없음을 확인하는 별도 소량 데이터
- `out/local-100k-20261118.validation.json`: 전체 파일 검증 결과
- `out/local-100k-20261118.plan.json`: MySQL 적재 작업 미리보기
- `out/local-100k-20261118.load-report.json`: 실제 적재 건수, 기존 데이터 보존, 서비스 복원 결과
- `out/local-100k-20261118/manifest.json`: 설정, 건수, 분포, 파일 SHA-256, 소스 기준
- `accounts.csv`: 회원별 ID, 이메일, 닉네임, 상태, 역할, 시나리오, 부하 계정 여부
- `vu_accounts.csv`: 기존 `tools/perf/tokens/generate_tokens.py`와 호환되는 부하 계정 목록
- `credentials.json`: 테스트 전용 공통 비밀번호와 BCrypt 해시
- `scenarios.json`: 대표 계정과 매칭 요청 예제
- `catalog_refs.json`: DB의 실제 상품/룰렛 보상 ID를 찾는 기준

생성 파일, 가상환경, 검증 보고서는 Git에서 제외된다. 계정 데이터는 합성 데이터이며,
이메일은 `example.test` 도메인이다. 일반·관리자 등 비탈퇴 계정의 비밀번호 해시는 실제
BCrypt cost 10이다. 고정 테스트 salt를 사용해 파일을 재현하며 실제 사용자 생성에는 사용하지 않는다.
기본 이미지 URL은 NULL로 두어 현재 프론트의 기본 이미지 fallback을 사용한다.

## 데이터 구성

기준 시각은 **2026-11-18 20:30:00 Asia/Seoul**이다. 회원 ID는 1,000,001–1,100,000이다.
별도 엔티티의 ID는 `start_id * 100 + 순번`이다. 명시적 ID 삽입은 해당 테이블의
AUTO_INCREMENT를 높일 수 있으며 정리 도구는 그 값을 되돌리지 않는다.

| 대상 | 건수 |
|---|---:|
| 회원 | 100,000 |
| 프로필 | 99,900 |
| 매칭 후보 | 99,850 |
| 프로필 취미 / 후보 취미 | 각각 299,600 |
| 프로필 태그 | 299,500 |
| 아이템 / 아이템 이력 | 각각 201,200 |
| 매칭 이력 / 채팅방 | 각각 10,000 |
| 채팅 메시지 / 차단 관계 | 160,000 / 100 |
| 주문 / 주문 항목 | 각각 10,000 |
| 주문 승인 지급 원장 / 상태 이력 | 2,000 / 8,000 |
| 룰렛 참여 이력 | 1,000 |
| 탈퇴 tombstone | 50 |

일반 회원 99,000명은 성별·20–27세·MBTI·학과·연락 빈도를 각각 독립 셔플하여 균등 배분한다.
특정 교차 조합까지 정확히 균등하게 강제하지 않는다. `random_key`는 0 이상 10억 미만에
분산한다. 취미는 실제 `DefaultHobby` 코드와 카테고리를 사용하고, 서로 다른 취미가
같은 카테고리에 속할 수 있어 취미 점수의 중복 카테고리 분기도 검증할 수 있다.

앞의 1,000명은 다음 20개 그룹을 각각 50명씩 포함한다.

| 그룹 | 상태 / 확인 대상 |
|---|---|
| active | 정상 기능 계정 |
| guest | ROLE_GUEST, 프로필 없음, 온보딩 |
| admin | ROLE_ADMIN, 관리자 로그인/권한, 프로필 없음 |
| pending | PENDING 상태, 현재 서비스의 허용/제한 정책 확인 |
| dormant / suspended / banned | 휴면·정지·차단 로그인 및 접근 제한 |
| withdrawn | 비밀번호 없음, 프로필 마스킹, 후보 없음, tombstone 있음 |
| not_matchable | 프로필·후보의 매칭 허용 false |
| no_items / zero_items | 아이템 행 없음 / 수량 0 |
| expired_items / expiring_items | 기준 시각 하루 전 / 한 시간 후 만료 |
| one_ticket / no_options | 매칭권 1개·옵션권 0 / 매칭권 10개·옵션권 0 |
| age_19 / age_27 / age_28 | 나이 경계 조건 |
| minimal_profile / rich_profile | 취미 2개·태그 없음 / 취미 5개·태그 5개 |

생년월일과 `candidate.age`는 현재 Java 구현의 **생일까지 완료된 연수 + 1**을 따른다.
달력 연도 차이만 사용하는 한국식 나이 공식으로 대신하지 않는다.
시나리오 계정을 포함하므로 전체 후보의 분포는 일반 회원의 균등 분포와 다르다.

부하 계정 500명은 일반 회원 중에서 고르며, 각 계정에 매칭권 2,000개·옵션권 6,000개를
넣는다. 이 계정에는 초기 매칭·주문·룰렛 이력이 없다. 회원 수 10만과 동시 사용자 수는
별개이며 한 계정을 여러 가상 사용자가 공유하지 않는다.

주문은 PENDING/APPROVED/REJECTED/CANCELED/EXPIRED 각각 2,000건이다. 승인 주문은
주문 항목·지급 원장·아이템·아이템 이력이 함께 존재한다. 실제 상품
`MATCHING_TICKET_1`의 가격과 구성품이 기대값과 다르면 적재가 중단된다.
룰렛은 FREE 꽝 500건과 SPECIAL 상품권 미지급 500건으로 일일 제한·관리자 지급 상태를
확인한다. 초기 지급 원장과 미발행 outbox를 동시에 만들어 중복 지급을 유발하지 않는다.
상품·보상 마스터는 앱 초기화 코드가 만든 기존 행을 참조하며 별도 생성하거나 수정하지 않는다.

## 오프라인으로 실행해도 되는 명령

가상환경과 의존성은 이번 작업에서 설치했다. 다른 환경에서 다시 준비할 경우:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

```bash
.venv/bin/python validate.py out/local-100k-20261118
.venv/bin/python mysql_tool.py plan out/local-100k-20261118
.venv/bin/python mysql_tool.py load out/local-100k-20261118
.venv/bin/python mongo_tool.py plan out/local-100k-20261118
.venv/bin/python -m unittest -v test_seed.py
```

위 `load`는 **미리보기**다. `--apply`가 없으면 env 파일도 읽지 않고 연결 함수도 호출하지 않는다.
테스트에서는 소켓/DB 연결을 금지한 상태에서 이 기본 동작을 확인한다.
TSV에는 헤더가 있고 NULL은 `\N`으로 표현한다. 이 파일을 구형 `load_seed.sh`에 넘기지 않는다.

## 실제 적재 절차

기본 데이터는 이미 적재되어 있다. 아래는 새 환경이나 정리 후 다시 적재할 때의 절차다.
현재 실행된 서비스 상태에서 쓰기 옵션만 붙여 재적재하지 않는다.

1. `.env.local`로 서비스를 먼저 한 번 기동해 Hibernate 테이블 및 상품/보상 마스터를 만든다.
2. 서비스와 부하 발생기를 중지하되 MySQL/MongoDB는 실행 상태로 유지한다. 진행 중인 요청,
   Kafka 프로필/탈퇴 이벤트와 주문 스케줄러가 적재에 개입하지 않는 상태여야 한다.
3. 필요하면 기존 로컬 DB를 백업한다. 적재 도구는 기존 회원이나 카탈로그를 덮어쓰지 않는다.
4. 아래 사전 검사 후 적재한다. 환경변수는 `.env.local`의 `LOCAL_MYSQL_*`를 읽으며,
   현재 셸에 설정된 동일 변수는 파일보다 우선한다. RDS 변수는 사용하지 않는다.

```bash
# DB에 접속하지만 데이터를 쓰지 않는 실제 스키마/ID 충돌/카탈로그 검사
.venv/bin/python mysql_tool.py check out/local-100k-20261118

# 명시적으로 쓰기를 활성화하는 명령
.venv/bin/python mysql_tool.py load out/local-100k-20261118 --apply --services-stopped
.venv/bin/python mysql_tool.py verify out/local-100k-20261118

# 채팅 데이터를 사용할 경우. 먼저 같은 데이터의 MySQL 적재가 성공해야 한다.
.venv/bin/python mongo_tool.py load out/local-100k-20261118 --apply --services-stopped
.venv/bin/python mongo_tool.py verify out/local-100k-20261118
```

호스트는 loopback만, 프로파일은 local만 허용한다. MongoDB는 `127.0.0.1:27017`,
DB는 `comatching_chat`이며 `LOCAL_MONGO_PORT`로 포트만 바꿀 수 있다.
`--services-stopped`는 사용자의 상태 확인을 받는 플래그이지 프로세스를 종료하는 기능이 아니다.

MySQL은 일반적인 매개변수 바인딩 INSERT를 500행씩 보내므로 `local_infile` 변경이나
컨테이너 파일 복사가 필요 없다. 모든 업무 데이터와 소유권 목록을 한 트랜잭션에 넣고,
건수·각 컬럼·참조값 검증 후 커밋한다. FK와 unique 검사는 끄지 않는다. 오류나 경고가 나면
롤백한다. 10만 명과 연관 행을 넣는 동안에는 undo/redo 공간과 실행 시간이 필요하다.

소유권 메타데이터 테이블 `_local_seed_runs`, `_local_seed_members` 두 개는 첫 실행에 만든다.
이 DDL은 업무 데이터 트랜잭션보다 먼저 커밋되므로 적재가 실패해도 빈 메타 테이블은 남을 수 있다.
동일 데이터 재실행은 DB가 초기 상태와 같으면 검증 후 종료한다. 데이터가 달라졌으면 자동 삭제하지
않고 중단한다. 새 데이터의 ID·이메일·닉네임 충돌도 중단 사유다.

MongoDB standalone과 MySQL 사이에는 단일 트랜잭션이 없다. MySQL에 `loading` 상태를
먼저 기록하고 채팅을 적재한다. 중간 실패 시 파일을 다시 덮어 넣지 말고 아래 Mongo 정리 후
재적재한다. 실패 상태에서는 MySQL 정리도 막아 소유권 근거가 먼저 없어지지 않게 한다.

## 초기 상태로 되돌리기

서비스와 부하 발생기가 중지된 동안 **항상 Mongo 정리 → MySQL 정리** 순서로 실행한다.
Mongo 파일을 직접 적재하지 않았더라도 API 테스트가 채팅방을 만들 수 있기 때문이다.
MongoDB를 사용할 수 없으면 MySQL 정리만 먼저 진행하지 않는다.

```bash
.venv/bin/python mongo_tool.py clean out/local-100k-20261118 --apply --services-stopped
.venv/bin/python mysql_tool.py clean out/local-100k-20261118 --apply --services-stopped
```

원래 manifest와 DB에 기록된 정확한 회원 ID 목록을 확인한다. 회원 ID가 일정 값 이상이라는
이유만으로 지우지 않는다. 테스트 중 추가된 프로필·아이템·주문/원장/outbox·매칭 이력·탈퇴
tombstone·채팅/메시지/차단도 그 회원의 관계를 따라 정리한다. 다른 회원과 섞인 매칭/채팅/차단,
테스트 관리자에 의한 다른 회원 주문 결정이 있으면 중단한다. 새로운 테이블이 추가되어 FK가
막으면 제약을 끄지 않고 해당 관계를 검토한다. 소유권 manifest를 잃어버린 데이터는 자동 정리하지 않는다.

정리 후 같은 파일을 다시 적재하면 기준 상태로 돌아온다. Redis 전체 삭제는 하지 않는다.
브라우저 테스트는 쿠키를 비우고 새로 로그인한다. 참여자 수는 user-service의 로컬 캐시
TTL 10초 이후 확인한다. 프로필 수정/탈퇴 이벤트가 남아 있다면 먼저 정상 소비를 마친 후
다음 초기화를 진행한다. 생성 데이터 직접 적재는 Kafka 전파 검증을 대신하지 않는다.

## 재생성 / 규모와 분포 변경

시간에 민감한 데이터는 **적재/검증 날짜에 맞춰 재생성**한다. 오래된 파일은 아이템 만료,
룰렛 당일 제한, 나이 및 주문 상태가 달라진다. `--as-of`, `--at`, 난수 seed, 설정이 같으면
파일 내용이 동일하다. 출력 폴더가 이미 있으면 덮어쓰지 않는다.

```bash
.venv/bin/python generate.py --out out/new-baseline --count 100000 \
  --scenario-count 1000 --vu-accounts 500 --as-of 2026-11-18 --at 20:30:00 \
  --dataset local-100k

# 1만 명 리허설
.venv/bin/python generate.py --out out/rehearsal-10k --count 10000 \
  --scenario-count 200 --vu-accounts 100 --as-of 2026-11-18 --dataset rehearsal-10k

# 동일 생성 규칙의 편중 분포. 동시에 적재하려면 별도 start-id/데이터셋이 필요하다.
.venv/bin/python generate.py --out out/skewed-100k --count 100000 \
  --scenario-count 1000 --vu-accounts 500 --as-of 2026-11-18 \
  --dataset skewed-100k --distribution skewed

# 정상 요청으로 후보 없음: 매칭 가능한 회원이 요청자 한 명뿐이다.
.venv/bin/python generate.py --out out/empty-pool --count 1000 \
  --scenario-count 200 --vu-accounts 1 --as-of 2026-11-18 \
  --dataset no-candidates --preset no-candidates
```

`no-candidates`는 기존 대량 데이터 및 다른 매칭 가능한 회원과 함께 적재하면 조건이 깨진다.
기준 데이터를 정리한 테스트 DB에서 별도로 사용한다. 기존 비테스트 회원은 자동으로 바꾸지 않는다.
일반 부하 계정은 두 종류 티켓을 보유하며 `SEED_TEST_PASSWORD`로 생성 비밀번호를 바꿀 수 있다.
`--history-count`, `--order-count`, `--roulette-count`, `--messages-per-room`으로 이력 양도 조정한다.

## 기능 확인과 부하 테스트 연결

실제 적재와 서비스 재기동 후 확인할 순서다. 현재 수행 완료라는 뜻이 아니다.

| 확인 | 계정 / 기대 결과 |
|---|---|
| 일반 로그인 | active/VU 계정, accessToken/refreshToken 쿠키, 일반 로그인은 302 이동 |
| 관리자 로그인 | admin 계정, 200 및 관리자 접근 |
| 온보딩 | guest 계정, 프로필 미완성 이동 |
| 상태 제한 | dormant/withdrawn 비활성, suspended/banned 잠금, pending 정책 확인 |
| 프로필 조회/수정 | minimal/rich 계정, 실제 취미·태그, 수정 후 Kafka 후보 동기화 확인 |
| 매칭 조건 | `scenarios.json`의 요청별 성별·필수 조건·이전 상대 제외 확인 |
| 아이템 부족/만료 | no_items/zero/expired/no_options, 실패 시 수량과 이력 변동 확인 |
| 마지막 매칭권 | one_ticket, 성공 한 번 후 다음 요청 실패 및 차감 이력 확인 |
| 유효 요청의 후보 없음 | 별도 no-candidates 데이터셋의 VU 계정으로 기본 요청 |
| 매칭 반복 | 같은 계정으로 요청 반복 시 이전 상대 제외, 요청 후 새 이력/채팅방 연결 확인 |
| 주문 | 상태별 목록, PENDING 승인 후 원장 1회 지급, 중복 승인 시 중복 지급 없음 |
| 룰렛 | 당일 재참여 제한, 상품권 미지급/지급 완료 상태 전이 |
| 채팅 | WAITING/ACTIVE, 메시지 순서, 읽음 상태, 차단 |

메일/S3/카카오/푸시, 외부 결제와 실시간 WebSocket/Kafka 연동은 해당 연동 환경과 실제 API
실행이 추가로 필요하다. 정적 데이터만으로 모든 기능의 성공을 보장하지 않는다.

기존 부하 테스트용 토큰 도구는 적재 후 필요할 때 다음처럼 연결할 수 있다. 토큰은 이번에 발급하지 않았다.
이 토큰 발급은 인증 부하 테스트를 대신하지 않으므로 로그인 성능을 측정할 때는 실제 로그인 API를 쓴다.

```bash
.venv/bin/python ../../tokens/generate_tokens.py --env ../../../../.env.local \
  --accounts out/local-100k-20261118/vu_accounts.csv \
  --out out/local-100k-20261118-tokens.csv
```

성능 측정은 매회 동일한 기준 상태와 별도 VU 계정을 사용한다. 기존 JMeter 파일의
계정 CSV 경로·게이트웨이 주소·실행 환경 전제는 실행 전에 맞춘다.

## 검증 범위

오프라인 검증은 체크섬, 행 수, PK/이메일/닉네임, enum, 참조 관계, 생년월일-나이,
프로필-후보 일치, 취미 중복도, 아이템 잔액-이력, 승인 지급 원장, 룰렛 일일 키,
MySQL 매칭-Mongo 채팅방-메시지 연결과 마지막 메시지 일치를 검사한다.
실제 로컬 DB의 스키마·카탈로그·충돌 검사와 INSERT, 커밋 후 전체 컬럼/문서 비교를 통과했다.
실제 DB 오류를 강제로 발생시키는 롤백 시험과 로그인/업무 API 동작 검증은 하지 않았다.
Mongo Extended JSON의 날짜는 `tz_aware=True`로 읽어 실제 MongoDB 조회 결과와 같은
시간대 표현으로 비교한다. BSON 왕복 검사를 포함한 오프라인 회귀 테스트 19개가 통과했다.
