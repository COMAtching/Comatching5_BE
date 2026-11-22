# 로컬 실행과 운영 설정 분리

## 설정 파일의 역할

각 서비스의 설정은 다음 세 파일로 나눈다. `application.yml`은 항상 읽고, `SPRING_PROFILES_ACTIVE`가 선택한 환경 파일을 함께 읽는다.

| 파일 | 책임 |
|---|---|
| `application.yml` | 서비스 이름·포트, 직렬화, OAuth provider, JWT 만료, 커넥션 풀, 기능 정책, 메트릭 등 공통 설정 |
| `application-local.yml` | 노트북 DB, localhost 서비스 주소, 로컬 callback·쿠키·CORS, 개발용 상태 노출 |
| `application-aws.yml` | RDS·컨테이너 주소, 운영 callback·쿠키·CORS, 스키마 검증, 운영 상태 노출 |
| `.env.local` / `.env.prod` | `SPRING_PROFILES_ACTIVE`, 비밀값, 환경별 설정값 |

여러 서비스가 공유하는 로컬 Redis/Kafka/JPA 기본값은 `common-module/src/main/resources/local-infrastructure.yml`에 두고 local 파일에서만 import한다. Feign 타임아웃은 기존 `feign-resilience.yml`을 공통 파일에서 import한다.

공통 YAML에 로컬 연결 주소를 두거나 `spring.profiles.active`를 하드코딩하지 않는다. env의 프로파일 값이 실제 실행 환경을 선택하며, 프로파일을 생략한 기동은 지원하지 않는다.

## 실행 환경

- JDK 17. macOS에서는 `run-local.sh`가 설치된 JDK 17을 선택한다.
- 노트북 MySQL 실행(기본 127.0.0.1:3306). Homebrew 설치본은 `brew services start mysql`.
- Docker Desktop 실행(Redis/MongoDB/Kafka용). Gradle은 저장소의 `./gradlew` 사용.
- 애플리케이션: gateway 8080, user 9000, matching 9001, chat 9003, notification 9005, item 9006.
- 인프라: **호스트 MySQL 3306**, Docker Redis **6380**, MongoDB 27017, Kafka 9092. Docker에 MySQL은 만들지 않는다.
- 시스템 Redis 6379와 별개이며, `local` 프로파일에서는 6개 서비스 모두 6380을 사용한다.

## 환경변수 준비

```bash
cp .env.local.example .env.local
openssl rand -base64 64 | tr -d '\n'  # 출력값을 JWT_SECRET에 입력
openssl rand -hex 32                  # 출력값을 INTERNAL_SERVICE_TOKEN에 입력
```

실제 env 파일은 Git 제외 대상이다. `.env.local`에는 `SPRING_PROFILES_ACTIVE=local`, `.env.prod`에는 `SPRING_PROFILES_ACTIVE=aws`를 둔다. Spring은 주입된 환경변수에서 프로파일을 선택한다. `.env` 파일 자체를 자동으로 읽는 기능은 없으므로 실행 도구가 파일을 읽어야 한다.

### IntelliJ

1. Run → Edit Configurations에서 각 Spring Boot 실행 구성의 **Environment variables → .env files**에 `$PROJECT_DIR$/.env.local`을 지정한다. 현재 로컬 실행 구성에는 이미 지정되어 있다.
2. 별도의 **Active profiles**, `-Dspring.profiles.active=...`, `--spring.profiles.active=...`는 비운다. 이 설정들은 env보다 우선해 env 변경을 무시하게 만든다.
3. 실행한다. 시작 로그에서 `The following 1 profile is active: "local"`을 확인한다.

이후 프로파일은 로드하는 env 파일의 `SPRING_PROFILES_ACTIVE` 값으로 바꾼다. 값 변경 후 서비스를 재시작해야 적용된다. `aws`를 선택하면 RDS 등 그 환경의 필수 값도 필요하므로, AWS 실행 구성은 필요한 값이 들어 있는 `.env.prod`를 선택한다. 프로파일 변경이 다른 env 파일을 자동으로 읽거나 그 파일의 비밀값을 가져오지는 않는다.

### 스크립트 / Docker Compose

`run-local.sh`는 `.env.local`을 export하고 JVM에 그대로 전달한다. 프로파일/DDL을 명령행으로 덮어쓰지 않는다. 이 스크립트는 노트북 MySQL과 로컬 컨테이너를 준비하므로 `local`만 허용한다.

운영 Compose는 `.env.prod`의 프로파일을 읽는다. 운영 서버의 기존 `.env.prod`에도 **배포 전에 `SPRING_PROFILES_ACTIVE=aws`를 추가**해야 한다. 빠져 있으면 Compose 설정 검증 단계에서 중단된다.

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod config --quiet
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --no-build
```

일반 `java -jar` 또는 `bootRun` 실행은 먼저 해당 env를 환경변수로 로드한다.

```bash
set -a
source .env.local
set +a
./gradlew :user-service:bootRun
```

`--skip-build`나 기존 JAR로 실행할 때는 YAML 변경이 반영되지 않으므로 먼저 재빌드한다.

로컬 MySQL 접속은 `.env.local`의 `LOCAL_MYSQL_HOST`, `LOCAL_MYSQL_PORT`, `LOCAL_MYSQL_USERNAME`, `LOCAL_MYSQL_PASSWORD`로 설정한다. 기본값은 `127.0.0.1:3306`, root, 빈 비밀번호다. 실제 설치본의 계정 설정에 맞춘다. 실행 스크립트는 MySQL에 연결할 수 없으면 중단하며, 별도 Docker MySQL을 생성하지 않는다.

운영에서 가져온 백엔드 env는 `.env.prod`로 보관한다. 로컬 파일에 필요한 외부 연동 변수만 복사한다:

- `SMTP_PASSWORD`: 메일 발송. 발신 계정이 다르면 Spring mail username도 맞춘다.
- `AWS_ACCESS_KEY`, `AWS_SECRET_KEY`, `AWS_S3_BUCKET`: 프로필 이미지 업로드.
- `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `KAKAO_ADMIN_KEY`: 카카오 로그인/연결 해제.
- `FCM_SERVICE_ACCOUNT_B64`: 서비스 계정 JSON의 Base64 한 줄. 대안으로 Git 제외된 `notification/src/main/resources/serviceAccountKey.json` 사용.

로컬 JWT/내부 토큰은 새 값을 유지한다. RDS/Redis/Kafka 주소, 운영 프로파일, 운영 쿠키 도메인, `SPRING_*` 접속 설정은 로컬 env에 복사하지 않는다.

초기 템플릿은 외부 서비스에 더미값을 사용한다. 백엔드 기동과 로컬 API 점검은 가능하지만 이메일 회원가입, 카카오 로그인, S3 업로드는 실제 설정을 넣기 전에는 동작하지 않는다. 이메일 회원가입은 인증 완료를 검사하므로 SMTP를 복구하거나 별도 로컬 테스트 계정을 준비한다.

`FCM_REQUIRED=false`는 키가 없어도 notification을 기동하게 한다. 푸시를 확인할 때 실제 키를 채우고 `true`로 바꾼다. 값이 있는 키가 잘못된 경우에는 초기화 오류가 발생한다.

## 로컬과 운영 선택

| 구분 | 로컬 개발 | 운영 배포 |
|---|---|---|
| env | `.env.local` | `.env.prod` |
| env의 `SPRING_PROFILES_ACTIVE` | `local` | `aws` |
| 실행 | `./run-local.sh` | 기존 서버의 배포 workflow / prod compose |
| MySQL | 노트북 설치본 127.0.0.1:3306 | RDS |
| 스키마 | `update` | 기존 `JPA_DDL_AUTO` / 기본 `validate` |
| 쿠키 | secure=false, domain 빈 값 | 운영 도메인/HTTPS 설정 |
| 로그인 완료 이동 | localhost:3000 | 운영 CLIENT_URL |
| 카카오 callback | localhost:8080/login/oauth2/code/kakao | srv.comatching.site/login/oauth2/code/kakao |

로컬 Kakao Developers 설정에도 `http://localhost:8080/login/oauth2/code/kakao`를 등록한다. 프론트 주소 `CLIENT_URL`과 카카오 callback `KAKAO_REDIRECT_URI`는 별개다.

## DB 초기화와 데이터 유지

`database/init/01-create-databases.sql`은 빈 DB 3개만 만든다:

- comatching_user
- comatching_matching
- comatching_item

`run-local.sh`는 `database/init/*.sql`을 파일 이름 순서로 실행한다. `02-create-matching-outbox.sql`은 매칭 outbox를, `03-create-member-outbox.sql`은 회원 outbox를, `04-create-order-grant-outbox.sql`은 아이템 지급 완료 outbox를 만든다. `05-create-notification-delivery.sql`은 `comatching_notification` DB와 메일·푸시 발송 원장을 만든다. IntelliJ에서 서비스만 실행할 때는 해당 서비스의 SQL을 로컬 MySQL에 먼저 적용한다.

나머지 테이블과 엔티티에 선언된 인덱스, 유니크/FK 제약은 첫 서비스 기동 시 Hibernate가 생성한다. 로컬 `ddl-auto=update`이므로 재시작할 때 기존 테이블/데이터를 삭제하지 않는다. IntelliJ에서 서비스만 시작하면 init SQL은 실행되지 않으며, 로컬 Hibernate 설정이 테이블 생성을 담당한다.

`database/init`에는 반복 실행 가능한 생성 SQL만 둔다. 기존 테이블 변경·데이터 이전 SQL은 별도 migration으로 관리한다. 운영은 기본 `ddl-auto=validate`이고 이 init 디렉터리를 자동 실행하지 않으므로, 각 서비스 배포 전에 해당 outbox 생성 SQL(02·03·04)을 대상 DB에 적용한다. 기존 매칭·주문 알림 테이블의 정리 인덱스는 `db/migrations/20261119_01_add_outbox_cleanup_indexes.sql`로 추가한다.

기존 주문이 있는 DB에서는 item-service와 주문 쓰기를 중지한 뒤, 새 버전을 시작하기 전에 `db/migrations/20261120_01_split_order_grant.sql`을 한 번 적용한다. Hibernate가 컬럼만 먼저 만들면 기존 지급 원장과의 대조가 빠진다. SQL은 모든 구성품의 원장이 일치하는 승인 주문만 `GRANTED`로 분류한다. 기존 대기 주문과 지급이 불분명한 승인 주문은 `REVIEW_REQUIRED`로 남겨 자동 지급과 재승인을 막는다. 빈 로컬 DB는 기존처럼 Hibernate로 생성한다.

운영에서 지급 완료 알림을 활성화할 때는 05번 SQL과 새 DB의 애플리케이션 계정 읽기·쓰기 권한을 준비한다. user-service의 내부 수신 주소 조회 API와 운영 Kafka 토픽을 먼저 배포한 뒤 notification을 기동한다. 두 그룹은 처음 기동할 때 토픽에 보관된 지급 완료 이벤트부터 소비한다.

기존 주문 DB에는 환불 API 배포 전에 `db/migrations/20261120_02_order_refund.sql`을 한 번 적용한다. 기존 행의 환불 상태는 `NONE`으로 초기화되므로 과거 수동 이체 내역이 있으면 해당 주문을 먼저 대조한다. 운영에서는 `kafka-topic-init`으로 `refund-completed` 및 환불 메일 DLT·replay를 준비하고, 구버전 item-service의 지급 워커·관리자 트래픽을 내린 뒤 새 환불 경로를 연다.

현재 저장소에는 회원 이메일/프로필, 매칭 이력, 주문 지급 이력, 룰렛 일일 참여 제한 등의 제약과 매칭/주문 조회 인덱스가 엔티티에 선언되어 있다. 상품과 룰렛 기본 데이터는 초기화 코드가 만든다. `db/migrations`의 룰렛 DDL/공지 소유권 이전 SQL은 기존 운영 DB를 위한 작업이므로 새 로컬 DB에 일괄 실행하지 않는다.

운영에만 수동 추가한 인덱스·트리거·프로시저는 코드만으로 확인할 수 없다. 운영과 정확한 스키마 비교가 필요할 때 스키마 전용 덤프 또는 SHOW CREATE TABLE/SHOW INDEX 결과와 대조한다. 기본 로컬 실행을 위해 운영 데이터 덤프를 먼저 받을 필요는 없다.

MySQL 데이터는 노트북 설치본의 데이터 디렉터리에, MongoDB는 Docker named volume에 남는다. `stop-local.sh --all`도 호스트 MySQL은 종료하지 않는다. 다른 프로젝트가 함께 쓸 수 있으므로 MySQL 서비스는 별도로 관리한다. `docker compose down -v`는 MongoDB 데이터를 지우므로 일반 종료에는 사용하지 않는다. Redis/Kafka는 현재 로컬 compose에서 영구 볼륨을 사용하지 않는다.

이전 Docker MySQL에서 옮긴 DB의 SQL 백업은 `logs/mysql-migration/`에 보관한다(Git/Docker 빌드 제외). 기존 Docker MySQL 데이터 볼륨은 복구용으로 남아 있지만 실행 구성에서는 사용하지 않는다.

`tools/perf`의 과거 부하 테스트/TSV 적재 도구 일부는 Docker MySQL을 전제로 한다. 일반 앱 실행과는 별개이며, 노트북 MySQL에서 부하 테스트할 때는 대상·적재 경로·서버 설정을 먼저 재검토한다.

## 기동 / 확인 / 종료

```bash
brew services start mysql      # 호스트 MySQL이 꺼져 있을 때(Homebrew 설치본)
./run-local.sh                 # 호스트 MySQL 확인 + Docker 인프라 + 빌드 + 6개 서비스
./run-local.sh --skip-build    # 이미 빌드한 jar 사용
./run-local.sh --infra-only    # 인프라와 DB만 준비

tail -f logs/user-service.log
curl http://localhost:8080/api/auth/participants

./stop-local.sh                # 이 프로젝트가 띄운 서비스만 종료
./stop-local.sh --all          # 컨테이너도 종료. 호스트 MySQL과 데이터는 유지
```

스크립트는 기록한 PID와 jar 경로를 확인해 서비스만 종료한다. 다른 프로세스가 애플리케이션 포트를 쓰면 자동으로 죽이지 않고 중단한다.

브라우저/API 요청은 Gateway `http://localhost:8080`으로 보낸다. Swagger는 `http://localhost:9000/swagger-ui/index.html`에서도 확인할 수 있다. 서버 포트가 열리는 것과 외부 연동 성공은 별도로 점검한다.
