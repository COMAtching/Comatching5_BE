# 코매칭 입금 장부

Mac/Windows의 일반 Chrome에서 **사용자가 조회한 KB국민은행 거래내역 표를 JSON으로 저장**하는 검증용 확장 프로그램입니다. 빌드나 npm 설치 없이 `extension` 폴더를 로드할 수 있습니다.

다른 PC로 옮길 때는 ZIP을 풀고 [START_HERE.md](START_HERE.md)부터 보세요. [검증 기록](VERIFICATION.md)에 통과한 항목과 실계좌 확인이 필요한 항목을 구분했습니다.

KB 빠른조회 결과 탭에서 장부를 한 번 읽은 뒤 **자동 조회 시작**을 누르면 Chrome 세션에 최신 결과 한 페이지를 유지합니다. 전송을 연결하면 이전 회차와 겹치는 페이지를 읽고, 관측 행과 조회 완료 정보를 서버 대기함과 Kafka로 전달합니다. 팝업을 닫아도 대상 탭을 열어 두면 작동합니다. JSON 파일 저장은 수동입니다. **합성 거래는 Node 수집기가 30초마다 새 입금을 읽어 item-service로 전송**합니다. 두 프로그램은 같은 거래내역 파서를 씁니다.

## KB 입금 전송과 Kafka (확장 0.5.4)

확장에서 **서버로 입금 전송**을 켜면, 자동 조회가 끝날 때마다 읽은 각 페이지의 정상 입금 행을 최대 100개씩 Chrome 로컬 대기함에 저장합니다. 수동 조회는 **현재 입금 보내기**를 눌러 전송합니다. 저장된 요청은 `POST /api/v1/collector/deposit-observations`로 보내며 전체 관측 ID가 일치하는 HTTP 202 응답을 받은 묶음만 대기함에서 제거합니다.

### 서버 준비

1. 백엔드 저장소 루트에서 MySQL 8 이상의 `comatching_item` DB를 선택해 `db/migrations/20261124_01_bank_deposit_observation.sql`과 이어서 `db/migrations/20261125_01_bank_approval.sql`을 각 1회 적용합니다. 첫 마이그레이션 실행 예는 `mysql -u 운영계정 -p comatching_item < db/migrations/20261124_01_bank_deposit_observation.sql`입니다.
2. item-service 실행 환경에 `BANK_DEPOSIT_INGEST_ENABLED=true`와 `BANK_DEPOSIT_INGEST_TOKEN`을 설정합니다. 32자 이상의 수집 전용 무작위 토큰을 사용합니다. 확장의 입력칸에 같은 값을 넣습니다. 합성 API의 `BANK_PROBE_TOKEN`은 별도입니다.
3. 운영 Kafka에서 원본 관측 토픽 `kb-deposit-observed-v1`, 완료 토픽 `kb-deposit-scan-completed-v1` 및 `kb-deposit-scan-completed-v1.item-bank-approval.{DLT,replay}` 두 복구 토픽을 준비합니다. 수정된 `docker-compose.prod.yml`의 `kafka-topic-init`에는 파티션 1, 복제 3, 최소 동기 복제본 2로 생성하는 명령이 있습니다. 로컬 Kafka에서는 프로젝트의 단일 브로커 설정을 사용합니다.
4. 변경된 item-service, gateway-service와 관리자 프런트엔드를 함께 배포합니다. 로컬 확장은 `http://127.0.0.1:9006`을 사용하고, 운영은 `https://srv.comatching.site`의 수집 전용 POST 경로를 사용합니다. 연결은 기본으로 꺼져 있습니다.

### 수신 보장과 관측 ID

- 서버는 입금 관측과 Kafka 발행 대기 상태를 MySQL의 같은 행에 커밋한 뒤 202를 반환합니다. 같은 ID·같은 내용은 그대로 접수하고, 같은 ID·다른 내용이면 묶음 전체에 409를 반환합니다.
- 발행기는 대기 행을 선점하고 Kafka의 `acks=all` 성공 후 발행 완료를 기록합니다. 실패·중단 뒤에는 선점 기한(90초)이 지난 행을 같은 ID로 재발행합니다. Kafka ACK와 DB 완료 기록 사이에 중단되면 중복이 생길 수 있으므로 소비자는 `eventId`를 고유 키로 처리해야 합니다. 서버 접수 시각과 Kafka 발행 완료 시각은 별개입니다.
- Kafka 메시지 키와 JSON의 `eventId`는 확장의 `observationId`입니다. 값에는 계좌 별칭, 수집 시각, 거래 날짜·시각, 입금액, 의뢰인명, 통장 표시, 표시된 경우 은행 거래번호가 들어갑니다. 같은 시각·이름·금액의 실제 두 행을 각각 보존합니다. 다른 회차에 같은 은행 행이 다시 보이면 새 관측 ID로 들어갑니다. 서버는 완료 회차의 입금 조합별 개수를 기존 장부와 대조합니다. 승인 소비자는 완료 토픽을 구독합니다.
- 브라우저는 대기 기록을 유지하고 전송 실패 시 약 1분 뒤 재시도합니다. 400·409·413은 해당 묶음을 보류하고 뒤의 묶음을 계속 전송합니다. 원인을 확인한 뒤 **보류 묶음 다시 보내기**로 재시도합니다. 401은 토큰을 갱신하세요. Chrome을 완전히 재시작하면 대기함은 유지되고 토큰을 다시 입력해야 합니다. 프로필 삭제나 확장 제거 시 로컬 대기함도 삭제됩니다.
- 서버 확인은 DB의 `published_at`으로 할 수 있습니다. `SELECT COUNT(*) FROM bank_deposit_observation WHERE published_at IS NULL`은 아직 Kafka 발행 완료를 기록하지 않은 관측 수입니다.

### 회차 완료와 자동 승인

1. 확장은 첫 페이지부터 이전에 확인한 거래 초 전체를 읽고 되돌아오며 표를 대조합니다. 은행의 페이지 번호, 건수, 조회 기간 또는 표 갱신을 확인할 수 없으면 마지막 확인 위치를 유지합니다. 조회가 시작된 초의 거래는 다음 회차에서 대조합니다.
2. 원본 행을 접수한 뒤 `POST /api/v1/collector/deposit-observations/scans`로 회차, 계좌지문, 시간 범위, 페이지별 캡처 ID와 입금 개수를 보냅니다. 같은 계좌의 거래 초·입금자명·금액과 그 회차의 행 수로 장부를 유지합니다. 425는 선행 관측이 부족하다는 뜻이며 확장이 재시도 후 보류 묶음으로 표시합니다.
3. 관리자 **결제 승인 관리 → KB 계좌 입금 대조**에서 자동 승인을 켭니다. 켜기 전에 만든 유효 대기 주문도 대상입니다. 스위치를 끄면 수집은 계속되고, 처리 중이던 구간의 미승인 입금은 다시 켜도 수동으로 처리합니다.
4. 같은 이름·금액의 후보가 겹치면 모두 보류합니다. 운영자가 근거를 확인하고 미사용 입금과 주문을 하나씩 선택해 **연결 승인**을 누릅니다. 한 번 충돌한 주문은 후보 수가 줄어도 계속 보류합니다.
5. 7분간 정상 조회가 없거나 페이지 대조에 실패하면 Prometheus 알림 규칙이 작동합니다. 운영 알림 수신을 위해 Alertmanager 수신처와 `DLT_ALERT_WEBHOOK_URL`을 설정하세요. 장애 원인을 고친 후 내부 item-service의 `POST /api/internal/kafka/dlt/redrive?route=BANK_SCAN`에 기존 내부 인증을 사용합니다. 수집 전용 토큰과 운영자 세션은 용도가 다릅니다.

장부 대조 시 `bank_deposit_ledger`에서 한국 시각의 `paid_second` 범위를 지정해 `COUNT(*)`와 `SUM(amount_krw)`를 KB 입금 원본과 비교합니다. `bank_deposit_scan`에 회차별 범위, 페이지 수, 입금 관측 수, Kafka 발행 및 처리 시각을 기록합니다. 여러 회차의 관측 수에는 겹쳐 읽은 행이 포함됩니다. 은행이 이미 확인한 경계보다 과거 시각으로 거래를 뒤늦게 표시하는 경우에는 원본과 별도 대조가 필요합니다.

### 합성 입력으로 전체 경로 검증

백엔드 루트에서 `./gradlew :item-service:integrationTest --tests probe.DepositObservationKafkaIT --tests probe.BankApprovalIT`를 실행합니다. JDK 17과 Node 22.13 이상이 필요합니다. 테스트가 격리된 H2 DB와 Kafka 브로커를 띄워 확장 송신 함수 → Spring HTTP 수신 → Kafka 소비를 대조합니다.

## 합성 입금 30초 수집

백엔드 루트에서 아래 명령을 실행하면 임시 포트의 Spring 수신 API와 독립 H2 데이터베이스가 뜹니다. 테스트가 입금을 생성하고 HTTP 수신 결과를 원장과 대조한 뒤 서버를 종료합니다. JDK 17과 Node 22.13 이상이 필요합니다. Windows에서는 `gradlew.bat :item-service:verifyBankProbeSync`를 사용합니다.

```bash
cd /Users/popeye/Developer/comatching5/Comatching5_BE
./gradlew :item-service:verifyBankProbeSync
```

수집기는 첫 실행 시 기존 거래의 마지막 위치를 저장합니다. 이후 추가된 거래를 최대 200행씩 조회해 입금만 JSON으로 보냅니다. 매번 조회를 시작할 때 마지막 거래번호를 정하므로, 페이지를 읽는 중 들어온 입금은 다음 회차에 처리합니다. 수신 API가 해당 페이지의 거래번호 전체를 확인하면 SQLite에 처리 위치를 저장합니다. 응답을 받기 전에 종료되면 같은 페이지를 재전송합니다. 원장 ID와 거래번호의 조합을 서버 DB 기본키로 두어 재전송을 처리하고, 같은 키로 내용이 바뀌면 해당 요청 전체를 취소합니다.

직접 입금을 추가하며 보려면 다음 순서로 실행합니다.

1. 서버와 수집기 터미널에 동일한 검증 전용 `BANK_PROBE_TOKEN`을 설정합니다(32자 이상). 백엔드 루트에서 `./gradlew :item-service:bankProbeServer`를 실행합니다. 기본 주소는 `127.0.0.1:48766`이고 수신 기록은 `item-service/build/bank-probe/`의 H2 파일에 남습니다.
2. 수집기 터미널에서 `tools/bank-transaction-probe`로 이동해 `npm run sync -- watch`를 실행합니다. 첫 로그가 나오면 기준점이 저장된 상태입니다.
3. 같은 폴더의 다른 터미널에서 `npm run sync -- append 2000`, 이어서 `npm run sync -- append 73 출금`을 실행합니다. 다음 조회에 입금 2,000건(200만 원)이 전송됩니다. 새 거래가 없는 회차는 HTTP 요청이 0건입니다. Ctrl+C로 종료할 수 있습니다.
4. 수집기를 다시 시작하면 같은 원장 파일과 수신 URL의 마지막 위치부터 이어갑니다. 원장과 처리 위치는 `output/sync/journal.sqlite`에 있습니다. 다른 디렉터리를 쓸 때는 수집기와 생성기에 같은 `BANK_PROBE_JOURNAL` 절대 경로를 지정합니다.

수신 경로는 `POST /probe/synthetic-deposits`이고, `GET /probe/synthetic-deposits/{streamId}?after=0`은 저장한 입금을 번호순으로 최대 200건 반환합니다. 둘 다 검증 전용 Bearer 토큰을 사용합니다. 별도 검증 서버를 지정하려면 수집기의 `BANK_PROBE_URL`을 전체 HTTPS 수신 URL로 설정합니다. 같은 수신 DB를 가리키는 URL을 바꾸면 별도 구독으로 인식해 새 기준점을 잡습니다. 일반 item-service에서 이 API를 열려면 `comatching.bank-probe.enabled=true`와 전용 토큰이 필요합니다. 검증에 사용하는 테이블은 `synthetic_probe_deposit`입니다.

이 실험에서 합성 원장은 거래를 추가하는 순간 재조회에도 유지되는 일련번호를 부여합니다. 실제 KB 화면에서 얻는 행 번호나 캡처 ID는 매번 바뀔 수 있습니다. 실계좌에 적용할 때는 안정적인 거래 식별자와 누락 없는 조회 범위를 확인해야 합니다.

## 토스 타행 조회에 관한 이전 조사

2026-11-22 기준 확인한 사실과 아직 확인하지 못한 내용을 구분합니다.

| 항목 | 상태 |
| --- | --- |
| KB PC 인터넷뱅킹의 다른금융 통합거래내역조회 메뉴 | 공식 페이지와 실제 로그인 진입 화면 확인 |
| 타 기관에서 토스뱅크 정보를 조회하는 오픈뱅킹 경로 | 관련 공식 약관에서 경로 확인. 특정 KB 계좌의 연결 성공을 의미하지 않음 |
| 사용자의 KB 계정에서 토스뱅크 선택·연결 | 미검증. 사용자가 직접 연결해야 함 |
| 연결된 토스뱅크의 **거래내역**이 KB PC에 표시되는지 | 미검증. 잔액 조회 성공과 구별해야 함 |
| 실제 KB 표의 열 이름, 일시 정밀도, 거래 고유번호, 페이지 제한 | 로그인된 조회 화면으로 검증 필요 |
| Mac의 실제 은행 로그인·보안 프로그램 호환 | 미검증. 일반 Chrome 사용을 전제로 구현 |

첫 실계좌 검증 대상은 보유 중인 KB국민은행 계좌입니다. 토스 타행 조회는 선택할 수 있는 별도 경로입니다.

공식 근거:

- [KB 다른금융 통합거래내역조회](https://obank.kbstar.com/quics?page=C101762): 실제 진입 시 로그인이 필요했습니다.
- [하나은행 공식 제공 약관 모음](https://image.kebhana.com/cont/download/documents/provide/0000020210225_20250901.pdf): 토스뱅크 오픈뱅킹 관련 약관 포함. KB의 PC 거래내역 지원·갱신 주기를 보증하는 자료는 아닙니다.
- [Chrome activeTab](https://developer.chrome.com/docs/extensions/develop/concepts/activeTab), [Chrome scripting](https://developer.chrome.com/docs/extensions/reference/api/scripting): 사용자의 확장 프로그램 실행 시 현재 탭에 임시 접근하는 구현 근거입니다.

## 실제 계좌로 실행하기

1. 보유 중인 KB 계좌에서 비교할 입금 한 건과 그 날짜를 고릅니다.
2. Mac의 Chrome 주소창에 `chrome://extensions`를 입력합니다. **개발자 모드 → 압축해제된 확장 프로그램을 로드합니다**를 선택하고 아래 폴더를 지정합니다. Windows에서도 같은 `extension` 폴더를 사용합니다.

   ```text
   /Users/popeye/Developer/comatching5/Comatching5_BE/tools/bank-transaction-probe/extension
   ```

3. 같은 Chrome에서 [KB 개인뱅킹 거래내역 조회](https://obank.kbstar.com/quics?page=C102264)에 로그인하고 해당 KB 계좌와 날짜를 선택해 거래 목록을 조회합니다. 빠른조회 등록 계좌의 별도 검증 경로는 [다른 PC에서 시작하기](START_HERE.md)에 있습니다.
4. 확장 프로그램 아이콘을 누르고 실제 조회한 은행과 계좌 별칭을 지정합니다. 별칭은 `행사입금`처럼 쓰며 계좌번호를 입력할 필요는 없습니다.
5. **거래내역 읽기**를 누르면 표 한 개의 열이 인식되는 경우 합계와 거래 목록이 표시됩니다. **표와 열 설정**에서 표·제목행·열을 바꾸고 **선택한 열 적용**을 누를 수 있습니다.
6. 은행 화면과 정상 건수·입금/출금 합계·거래내용을 비교한 뒤 **JSON 저장**을 누릅니다. 다운로드 폴더에 저장됩니다. 팝업을 닫으면 추출 상태가 사라지므로 저장 전 다른 화면을 누르지 마세요.

열 선택의 두 가지 방식:

- **입금액/출금액이 분리된 표:** 해당 열을 각각 선택하고 `거래금액`, `입출금 구분`은 사용하지 않습니다. 입금 전용 조회라면 입금액만 선택할 수 있습니다.
- **거래금액과 입출금 구분이 있는 표:** 이 두 열을 선택하고 `입금액`, `출금액`은 사용하지 않습니다.

`순번`, `No`, `번호`는 **은행 거래고유번호가 아닙니다.** 실제 은행 거래 ID인지 확인되지 않은 열은 고유번호에 지정하지 마세요. `거래내용`에 있는 값도 자동으로 실제 입금자명이나 주문번호라고 해석하지 않습니다.

## 설치 없이 합성 데이터로 확인하기

Node.js 22 이상이 있다면 외부 패키지 설치 없이 실행합니다.

```bash
cd /Users/popeye/Developer/comatching5/Comatching5_BE/tools/bank-transaction-probe
npm test
npm run demo
```

브라우저에서 `http://127.0.0.1:48765/`를 엽니다. 확장 프로그램과 같은 파서·추출기·미리보기 코드를 사용합니다. 샘플은 자동으로 읽습니다. **확인 필요**를 누르면 오류 사유가 보이고, **새로 읽기**를 누르면 전체 목록으로 돌아옵니다. 원본 페이지 `/fixtures/bank.html`에는 **DOM 검증 실행**이 있습니다. 로컬 서버는 합성 화면 코드만 제공하며 업로드나 실제 계좌 연결 기능이 없습니다. 종료는 터미널에서 Ctrl+C입니다.

Chrome 확장 자체의 실행 경로도 확인하려면 `extension` 폴더를 로드한 뒤 `http://127.0.0.1:48765/fixtures/bank.html`에서 확장 프로그램을 실행하세요. 웹 데모 테스트만으로 Chrome 권한·실제 은행 프레임 호환성이 검증되지는 않습니다.

## JSON 해석

```json
{
  "schemaVersion": "bank-transaction-probe/v1",
  "account": {
    "alias": "행사입금",
    "bank": "kb",
    "verification": "user_supplied_unverified"
  },
  "coverage": "rendered_page_only",
  "canAutoApprove": false,
  "transactions": [{
    "observationId": "<capture-id>:0:0:2",
    "bankTransactionId": null,
    "transactionDate": "2026-11-22",
    "transactionTime": "14:01:03",
    "timePrecision": "second",
    "occurredAt": "2026-11-22T14:01:03+09:00",
    "direction": "CREDIT",
    "amountKrw": 1000,
    "balanceKrw": 11000,
    "description": "CM7K2P",
    "summary": "타행이체",
    "sourceRow": 2
  }]
}
```

위 코드는 일부 필드만 보여주는 합성 예시입니다. 실제 파일에는 추출 시각, 출처·열 매핑, 건수·합계, 경고, 제외 및 오류 행 번호가 함께 들어갑니다.

- `account.bank`와 별칭은 사용자가 지정한 값입니다. 화면의 계좌 소유권·은행·계좌번호를 검증한 결과가 아닙니다.
- `dataKind: synthetic_demo`는 로컬 합성 데이터입니다. 은행 도메인에서 추출하면 `bank_page_observation`이며, 이것도 은행이 서명하거나 진위를 보증한 자료는 아닙니다.
- `observationId`는 **이번 추출 안에서 행을 구분하는 번호**입니다. 재조회 중복 제거 키나 은행 거래번호가 아닙니다.
- KB 빠른조회의 두 줄짜리 거래에서 `description`은 내통장표시내용, `counterparty`는 의뢰인/수취인입니다. `sourceRow`와 `sourceEndRow`는 한 거래를 구성하는 원본 행 범위입니다.
- 동일한 일시·금액·거래내용·잔액인 행도 모두 유지합니다. 반복 입금을 하나로 합치지 않습니다. 같은 페이지를 두 번 저장하면 거래도 두 번 관측됩니다.
- `occurredAt`은 초까지 표시된 경우에만 생성합니다. 분 단위 또는 날짜만 있으면 `null`로 두고 `timePrecision`으로 구분합니다. 한국 계좌의 KST를 전제로 합니다.
- `counts.accepted`는 파싱에 성공한 **입금과 출금 전체**입니다. 오류가 있어도 정상 행은 내보내되 `rejectedRows`에 행 번호/사유를 남깁니다. 오류 행의 원문 전체는 저장하지 않습니다.
- 대표 오류: `invalid_money`(금액 형식/가림), `invalid_date`/`invalid_time`, `unknown_direction`, `both_credit_and_debit`, `irregular_data_row`(열 개수·데이터 병합 셀).
- 화면의 다음 페이지, 아직 로드하지 않은 ‘더보기’, 가상 스크롤의 미표시 행은 포함되지 않습니다. 합계가 맞더라도 전체 조회 기간을 모두 가져왔다는 뜻은 아닙니다.

## 검증 범위와 다음 단계

현재 자동 테스트는 금액/날짜 엄격 검사, 다단 제목, 수동 열 지정, 입출금 구분, 중복 행 보존, 오류·합계 행 처리, 도메인·권한 제한을 확인합니다. **합성 10,000건 → 12,000건** 테스트는 변환 과정에서 같은 거래를 누락하지 않는지 확인합니다. 은행이 실제로 그만큼의 행을 보여주는지, 이 조회 주기를 허용하는지, 프레임이 접근 가능한지는 별도입니다.

실계좌 검증 완료 조건:

1. KB 개인뱅킹 조회 화면에 해당 KB 계좌의 개별 입금 거래가 보인다.
2. 선택한 한 계좌·한 페이지의 거래 건수, 입출금액, 날짜·시각, 통장 표시가 JSON과 맞는다.
3. 같은 1,000원 입금 여러 건이 각각 나온다. 오류 행이 있다면 이유를 확인한다.
4. 새 거래 발생 전후 수동으로 다시 조회했을 때 새 행이 나온다. 실제 갱신 지연을 기록한다.
5. 거래 고유번호·페이지 이동 방식·로그인 유지시간을 확인한다.

은행 연결 후에는 실제 조회 갱신과 페이지 이동 방식을 확인합니다. 거래 식별자와 조회 범위가 검증되면 합성 수집에서 검증한 재시도 방식을 적용할 수 있습니다.

합성 수집은 컴퓨터가 깨어 있고 수집기 프로세스가 실행 중일 때 동작합니다. 저장된 합성 원장을 유지하면 종료 중에 쌓인 거래도 다음 실행에서 따라잡습니다. 은행 조회는 로그인 세션 유지시간도 직접 확인해야 합니다.

## 데이터 범위

확장은 사용자가 연 탭에 `activeTab`과 `scripting`으로 접근하고, Chrome의 `alarms`로 조회를 예약합니다. 로컬과 운영 모두 3분 간격입니다. `storage.session`에 실행 상태와 마지막 페이지를 보관합니다. KB의 HTTPS 도메인과 로컬 데모만 허용합니다. 수동 조회 또는 운영자가 시작한 자동 조회에서 접근 가능한 프레임의 **보이는 HTML 표 텍스트**만 수집합니다. 접근할 수 없는 프레임·카드형 UI·캔버스 표는 지원하지 않으며, OCR 대체 처리는 없습니다.

은행 입력칸 값, 쿠키, 은행 인증 토큰과 통신 응답은 수집하지 않습니다. 서버 전송은 운영자가 연결한 수신 서버에만 허용합니다. URL은 KB 메뉴 식별자를 제외한 쿼리와 해시를 제거합니다. 다만 은행 표에 표시된 이름·거래내용·잔액은 내려받은 파일에 포함되므로 실제 파일은 저장소 밖이나 무시되는 `output/`에 보관하세요. 다른 사람에게 검증을 요청할 때는 해당 값을 가립니다.

파일 구성: `extension/`은 배포 없이 로드할 프로그램, `fixtures/`는 합성 표, `test/`는 Node 테스트, `scripts/serve-demo.mjs`는 로컬 데모 서버입니다.
