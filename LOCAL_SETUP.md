# 로컬 실행 가이드

## 1. 사전 준비

| 필요한 것 | 확인 명령 |
|---|---|
| JDK 17 | `java -version` (17 이어야 함) |
| Docker Desktop 실행 중 | `docker info` |

JDK 17 이 없다면: `brew install --cask temurin@17`

## 2. 실행

```bash
cd ~/Documents/Comatching5_BE
./run-local.sh
```

이 스크립트가 하는 일:

1. `.env` 로드
2. `docker-compose.local.yml` 로 MySQL / Redis / MongoDB / Kafka 기동 후 healthy 대기
3. `./gradlew clean build -x test`
4. 6개 서비스를 순서대로 백그라운드 실행 + 포트 열릴 때까지 대기

두 번째부터는 빌드 생략 가능:

```bash
./run-local.sh --skip-build
```

종료:

```bash
./stop-local.sh          # 서비스만
./stop-local.sh --all    # 도커 인프라까지
```

로그: `tail -f logs/user-service.log`

## 3. 포트 구성

### 인프라

| 서비스 | 포트 | 비고 |
|---|---|---|
| MySQL | 3307 | root / `comatching12!@` |
| Redis | 6379 + 6380 | 동일 컨테이너를 두 포트로 매핑 |
| MongoDB | 27017 | DB `comatching_chat` |
| Kafka | 9092 | KRaft 단일 노드, 토픽 자동 생성 |

> Redis 를 두 포트로 연 이유: `user/matching/gateway` 는 `6380`, `item/chat/notification` 은 기본값 `6379` 를 바라보도록 코드가 작성되어 있음.

### 애플리케이션

| 서비스 | 포트 |
|---|---|
| gateway-service | **8080** |
| user-service | 9000 |
| matching-service | 9001 |
| chat-service | 9003 |
| notification | 9005 |
| item-service | 9006 |

## 4. Postman 사용법

**모든 요청은 게이트웨이(`http://localhost:8080`)로 보낸다.**

라우팅 규칙 (`gateway-service/src/main/resources/application.yml` 기준):

| 경로 | 대상 | 인증 |
|---|---|---|
| `/api/auth/login`, `/api/auth/signup`, `/api/auth/reissue`, `/api/auth/email/**`, `/api/profile/tags`, `/api/hobbies/categories`, `/oauth2/**` | user-service | 불필요 |
| `/api/auth/signup/profile`, `/api/auth/logout`, `/api/auth/withdraw`, `/api/members/**` | user-service | **필요** |
| `/api/matching/**` | matching-service | 필요 |
| `/api/chat/**`, `/ws/**` | chat-service | 필요 |
| `/api/items/**`, `/api/v1/**` | item-service | 필요 |
| `/api/fcm/**` | notification | 필요 |

인증이 필요한 라우트는 `AuthorizationHeaderFilter` 가 붙어 있으므로
`Authorization: Bearer <accessToken>` 헤더가 필요하다.

**추천 흐름**

1. `POST http://localhost:8080/api/auth/signup` 으로 계정 생성
2. `POST http://localhost:8080/api/auth/login` 으로 accessToken 획득
3. Postman 환경변수에 토큰 저장 후 나머지 API 호출

**Swagger** (게이트웨이 경유)

- user: http://localhost:8080/user-doc/swagger-ui/index.html
- matching: http://localhost:8080/matching-doc/swagger-ui/index.html
- chat: http://localhost:8080/chat-doc/swagger-ui/index.html

각 서비스 직접 접근도 가능: `http://localhost:9000/swagger-ui/index.html`

## 5. 직접 채워야 하는 값

`.env` 의 아래 항목은 더미값이다. **더미여도 서버는 정상 기동되고 대부분의 API 는 동작한다.**
해당 기능을 실제로 쓸 때만 교체하면 된다.

| 변수 | 없으면 안 되는 기능 | 발급처 |
|---|---|---|
| `SMTP_PASSWORD` | 이메일 인증코드 발송 | Google 계정 → 앱 비밀번호 |
| `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` / `AWS_S3_BUCKET` | 프로필 이미지 S3 업로드 | AWS IAM |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` / `KAKAO_ADMIN_KEY` | 카카오 소셜 로그인·연결끊기 | Kakao Developers |

추가로 **FCM 푸시**를 쓰려면 Firebase 콘솔에서 서비스 계정 키를 받아
`notification/src/main/resources/serviceAccountKey.json` 으로 저장한다.
없으면 기동 시 `❌ FirebaseApp Init Failed` 로그만 찍히고 서버는 정상 동작한다.

## 6. 주의사항 / 트러블슈팅

- **DB 데이터가 매번 초기화된다.** `user-service`, `item-service` 는 `ddl-auto: create` 라 재기동할 때마다 테이블을 다시 만든다. 데이터를 유지하려면 각 `application.yml` 에서 `create` → `update` 로 바꿀 것.
- **포트 충돌**: `lsof -i :8080` 등으로 확인. 로컬 MySQL 이 이미 3306 을 쓰고 있어도 무관하다(3307 사용).
- **Kafka 컨슈머 에러 로그**: 최초 기동 시 토픽이 없어 `UNKNOWN_TOPIC_OR_PARTITION` 경고가 뜰 수 있으나 자동 생성되므로 무시해도 된다.
- **첫 빌드가 느림**: QueryDSL + Spring Cloud 의존성 다운로드로 5~10분 걸릴 수 있다.
- **`Bad credentials` / 401**: `.env` 의 `JWT_SECRET` 을 바꿨다면 기존 토큰은 전부 무효다. 전체 서비스가 같은 값을 써야 한다.

## 7. 추가된 파일

```
docker-compose.local.yml            # 로컬 인프라
database/init/01-create-databases.sql  # DB 3개 생성
.env                                # 환경변수 (gitignore 됨)
run-local.sh / stop-local.sh        # 기동·종료 스크립트
LOCAL_SETUP.md                      # 이 문서
```
