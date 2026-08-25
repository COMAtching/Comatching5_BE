# Kafka 파티션과 메시지 키 개선

> "지금은 안 터지지만 늘리는 순간 터진다" — 파티션 증설의 선행 조건을 갖추고,
> 키로도 막을 수 없는 크로스 토픽 순서 역전까지 닫은 작업의 기록.

## 문제

모든 토픽이 브로커 auto-create 로 만들어져 **파티션 1개**였고, 프로듀서는
**메시지 키 없이** 발행하고 있었다.

```java
kafkaTemplate.send(TOPIC, event);   // key 없음 → 라운드로빈 분배
```

여기서 두 가지가 파생된다.

1. **컨슈머 병렬성이 1로 고정** — 파티션 1개면 컨슈머 그룹당 컨슈머 1개만
   배정된다. concurrency 를 올려도, 인스턴스를 늘려도 나머지는 유휴다.
   처리량 상한이 파티션 수에 묶여 있었다.
2. **파티션을 늘리는 순간 순서가 깨진다** — Kafka 의 순서 보장 단위는 토픽이
   아니라 파티션이다. 키가 없으면 같은 회원의 이벤트가 서로 다른 파티션으로
   흩어져 처리 순서가 뒤집힐 수 있다.

키 없이 파티션만 늘리면 같은 회원의 이벤트가 라운드로빈으로 흩어지고,
파티션이 다르면 처리 순서도 제각각이 된다.

```mermaid
flowchart LR
    subgraph U["user-service (키 없음)"]
        E1["회원 A 갱신 ①"]
        E2["회원 A 갱신 ②"]
    end
    subgraph T["profile-updates — 파티션 2개로 증설한 순간"]
        P0["파티션 0"]
        P1["파티션 1"]
    end
    E1 -->|라운드로빈| P0
    E2 -->|라운드로빈| P1
    P0 --> C0["컨슈머 스레드 0"]
    P1 --> C1["컨슈머 스레드 1"]
    C1 -.->|"② 가 먼저 처리되면"| R["① 이 덮어써서<br/>옛 프로필이 최종 상태 ❌"]
    C0 -.-> R
```

가장 위험한 조합이 `member-withdraw` 와 `profile-updates` 다. 두 토픽 모두
같은 `matching-service-group` 이 소비하고 **같은 `MatchingCandidate` 레코드를
건드린다.**

```mermaid
flowchart LR
    US[user-service]
    subgraph K["Kafka (개선 전)"]
        MW["member-withdraw<br/>파티션 1개"]
        PU["profile-updates<br/>파티션 1개"]
    end
    subgraph MS["matching-service-group"]
        L1["탈퇴 리스너<br/>removeCandidate"]
        L2["갱신 리스너<br/>upsertCandidate"]
    end
    MC[("MatchingCandidate")]
    US -->|키 없음| MW
    US -->|키 없음| PU
    MW --> L1
    PU --> L2
    L1 -->|삭제| MC
    L2 -->|생성·갱신| MC
```

```text
정상 순서   profile-updates(갱신) → member-withdraw(탈퇴)
            upsertCandidate()    → removeCandidate()      결과: 후보 삭제됨  ✅

역전 발생   member-withdraw(탈퇴) → profile-updates(갱신)
            removeCandidate()    → upsertCandidate()      결과: 탈퇴 회원이 후보로 부활  ❌
```

유실에 강하라고 만든 upsert 가 순서 역전에는 정확히 반대로 작용한다 —
지워진 뒤 도착한 갱신 이벤트가 후보를 다시 만들어 낸다.

## 바꾼 것

### 1. 프로듀서에 메시지 키(memberId) 부여 — 증설의 선행 조건

`UserEventPublisher` 의 세 토픽(`member-signup` · `member-withdraw` ·
`profile-updates`) 전부 `memberId` 를 키로 발행한다.

```java
stringKafkaTemplate.send(topic, String.valueOf(memberId), message);
```

같은 키는 같은 파티션으로 가므로 **회원 단위 순서는 파티션 수와 무관하게
보장되고, 서로 다른 회원끼리는 병렬 처리**된다.

```mermaid
flowchart LR
    subgraph U["user-service (key = memberId)"]
        A1["회원 A 갱신 ①"]
        A2["회원 A 갱신 ②"]
        B1["회원 B 갱신 ①"]
    end
    subgraph T["profile-updates — 파티션 3개"]
        P0["파티션 0"]
        P1["파티션 1"]
        P2["파티션 2"]
    end
    A1 -->|"key = A"| P0
    A2 -->|"key = A"| P0
    B1 -->|"key = B"| P2
    P0 -->|"① → ② 순서 유지"| C0["컨슈머 스레드 0"]
    P2 -->|"회원 간 병렬 처리"| C2["컨슈머 스레드 2"]
```

### 2. 토픽 스펙을 코드로 명시 — `KafkaTopicConfig`

`MatchingCandidate` 를 건드리는 두 토픽을 matching-service 에서 `NewTopic`
빈으로 선언했다(파티션 3, 복제 1). 파티션 수는 "얼마나 병렬로 소비해야
하는가"에서 나오고, 그 요구를 아는 쪽은 소비자다.

- `KafkaAdmin` 은 기동 시 기존 토픽의 파티션이 선언보다 적으면 늘려 주고
  줄이지는 못하므로 멱등하게 적용된다.
- 운영 환경(`auto.create.topics.enable=false`)에서 토픽이 없어 발행·구독이
  실패하는 문제도 함께 사라진다.
- 브로커 없는 테스트 환경은 `matching.kafka.topics.enabled=false` 로 토픽
  관리를 끈다(KafkaAdmin 이 기동 시 접속을 시도하며 타임아웃까지 기다리는
  것을 막는다).

### 3. 컨슈머 병렬성 활용 — `concurrency = 3`

두 `@KafkaListener` 의 concurrency 를 파티션 수와 같은 상수
(`KafkaTopicConfig.CANDIDATE_LISTENER_CONCURRENCY`)로 묶었다. 파티션 수보다
큰 값은 유휴 스레드만 만들기 때문에 두 숫자는 한 곳에서 관리한다.

### 4. 탈퇴 tombstone — 키로는 못 막는 역전을 닫는다

**키의 순서 보장 범위는 한 토픽의 한 파티션이다.** 탈퇴와 갱신은 서로 다른
토픽이므로 키를 걸어도 둘 사이의 순서는 보장되지 않는다(파티션이 1개였던
기존 구조에서도 마찬가지로 존재하던 창이다). 탈퇴가 먼저 처리되고 갱신이
늦게 도착하면 upsert 가 후보를 되살린다.

그래서 삭제를 "레코드 없음"이 아니라 **`WithdrawnMember` 레코드의 존재**로
남긴다.

- `removeCandidate` — tombstone 을 `saveAndFlush` 로 즉시 INSERT 한 뒤 후보를
  **잠금 조회(current read)** 로 지운다. 재전달에 멱등.
- `upsertCandidate` — tombstone 을 잠금 조회하고, 있으면 갱신 이벤트를
  무시한다.

```mermaid
sequenceDiagram
    participant US as user-service
    participant PU as profile-updates
    participant MW as member-withdraw
    participant L2 as 갱신 리스너
    participant L1 as 탈퇴 리스너
    participant DB as matching DB

    US->>PU: 프로필 갱신 발행 (key=A)
    US->>MW: 회원 탈퇴 발행 (key=A)
    Note over PU,MW: 서로 다른 토픽 — 둘 사이 소비 순서는 보장되지 않는다

    MW->>L1: 탈퇴가 먼저 소비됨 (역전)
    L1->>DB: tombstone(A) 삽입
    L1->>DB: 후보 A 삭제

    PU->>L2: 갱신이 뒤늦게 소비됨
    L2->>DB: tombstone(A) 잠금 조회
    DB-->>L2: 존재함
    Note over L2: upsert 건너뜀 — 탈퇴 회원 부활 차단 ✅
```

concurrency 3 에서 두 이벤트는 실제로 서로 다른 스레드에서 **동시에** 처리될
수 있고, 이 동시 실행 창은 순서만으로는 닫히지 않는다. 그래서 양쪽 모두
잠금이 필요하다.

- **upsert 쪽**: tombstone 조회가 잠금 조회(`PESSIMISTIC_WRITE`)다. 행이
  없으면 InnoDB 갭 락이 걸려서, 같은 회원의 탈퇴 트랜잭션이 tombstone 을
  넣으려면 upsert 커밋까지 기다려야 한다 — 두 트랜잭션의 직렬화 지점.
- **탈퇴 쪽**: 갭 락 대기가 풀린 뒤의 후보 삭제도 **잠금 조회(current
  read)** 여야 한다. MySQL REPEATABLE READ 에서 일반 조회는 트랜잭션 시작
  시점의 스냅샷을 읽기 때문에, 대기가 풀리는 동안 upsert 가 커밋한 후보를
  보지 못하고 삭제를 건너뛴다. 그러면 tombstone 은 남지만 후보도 남고,
  탈퇴 회원은 이벤트를 더 내지 않으므로 그 후보는 **영구히 매칭 대상으로
  잔존**한다. 잠금 조회는 스냅샷과 무관하게 최신 커밋을 읽어 이 구멍을
  막는다.

### 5. `auto.offset.reset=earliest` — 증설이 유실이 되지 않게

공통 컨슈머 팩토리는 yml 의 `spring.kafka.consumer.*` 를 읽지 않는 수동
구성이라, yml 에 뭐라고 적어도 실제로는 클라이언트 기본값 **latest** 가
적용되고 있었다. latest 는 파티션 증설과 만나는 순간 유실이 된다.

```text
증설 직후 새 파티션(p1, p2)에는 어떤 그룹의 커밋 오프셋도 없다
   → 프로듀서는 메타데이터 갱신 즉시 새 파티션으로 발행 시작
   → 컨슈머 그룹은 메타데이터 갱신(최대 5분) 후에야 새 파티션을 배정받음
   → latest: 배정 시점에 로그 끝으로 점프 → 그 사이 발행분 영구 유실
```

`member-withdraw` 는 notification 도 구독하므로 탈퇴 메일이 소리 없이
누락되고, matching 쪽은 tombstone 미기록·후보 미삭제로 이어진다. 팩토리에
`earliest` 를 명시했다 — 커밋 오프셋이 있는 기존 파티션에는 아무 영향이
없고, 오프셋이 없는 새 파티션만 처음부터 읽는다.

## 전환 절차 — 증설은 배포 순서가 반이다

키 지정(프로듀서, user-service)과 파티션 증설(`KafkaAdmin`, matching-service)
은 서로 다른 서비스에서 일어난다. 순서가 뒤집히면 키 없는 프로듀서가 3개
파티션에 이벤트를 뿌리는 구간이 생긴다.

```text
① user-service 배포        키 지정. 파티션이 아직 1개라 동작 변화 없음(무해)
② 컨슈머 lag 드레인 확인    p0 에 쌓인 백로그를 소진
③ matching-service 배포     KafkaAdmin 이 파티션 1 → 3 증설
```

이 순서대로 해도 전환 구간에는 한계가 남는다. 기존 백로그는 전부 p0 에
있고 새 메시지는 p1·p2 로 흩어지는데, 파티션별 소비 속도가 다르므로 **같은
회원의 옛 이벤트(p0)가 새 이벤트(p2)보다 늦게 처리될 수 있다.**
`upsertCandidate` 에는 버전·타임스탬프 비교가 없어 늦게 온 옛 프로필이 새
프로필을 덮어쓴다(다음 갱신 때 자연 복구). ② 로 창을 최소화하는 것이 현재의
대응이고, 이벤트에 버전을 실어 stale 갱신을 거부하는 것이 다음 단계다.

## 검증

| 테스트 | 방식 | 결과 |
|---|---|---|
| 같은 회원의 이벤트가 memberId 키로 같은 파티션에 발행 순서대로 쌓인다 | EmbeddedKafka, 3파티션 토픽에 실제 발행 후 키·파티션·오프셋 확인 (`UserEventPublisherIT`) | ✅ |
| 정상 순서: 갱신 → 탈퇴 ⇒ 후보 삭제 + tombstone | 실제 MySQL (Testcontainers, `CandidateServiceImplIT`) | ✅ |
| 역전 순서: 탈퇴 → 늦은 갱신 ⇒ 후보 부활하지 않음 | 〃 | ✅ |
| 탈퇴 이벤트 재전달·후보 없는 탈퇴 ⇒ 멱등 | 〃 | ✅ |
| 탈퇴하지 않은 회원의 upsert 는 그대로 동작 (신규 + 갱신) | 〃 | ✅ |
| **동시 실행**: upsert 가 갭 락을 잡고 진행 중일 때 탈퇴가 끼어들어도 후보가 남지 않음 | 실제 MySQL, 두 스레드·두 트랜잭션으로 교차 재현 | ✅ |

user-service · matching-service 전체 테스트 회귀 통과 (failures 0, errors 0).
구현 후 별도의 다각도 검증(Kafka 의미론·JPA 잠금·운영 설정)을 거쳐 발견된
스냅샷 읽기 구멍(동시 실행 시 후보 미삭제)과 latest 유실 창을 반영했다.

## 남은 한계 (다음 순서)

1. **DLT · 재시도 정책 없음** — 탈퇴 이벤트가 소비 실패로 유실되면 후보가
   잔존한다. tombstone 은 순서 역전을 막을 뿐 유실을 막지는 못한다.
2. **컨슈머 설정이 커스텀 팩토리에 가려 yml 이 적용되지 않는 문제**는 이번
   범위 밖. `auto.offset.reset` 만 유실 위험 때문에 코드에 명시했고, 나머지는
   `KafkaProperties` 기반 정정이 다음 단계다.
3. **갱신 이벤트에 버전·타임스탬프가 없다** — 같은 토픽 안에서는 키가 순서를
   보장하지만, 전환 구간·재처리처럼 순서가 흔들리는 상황에서 stale 갱신을
   거부할 수단이 없다.
4. **복제 계수 1** — 브로커가 1대라서다. 브로커 증설 시
   `KafkaTopicConfig.REPLICATION_FACTOR` 만 올리면 된다.
5. `chat-notification` 은 `roomId` 키가 자연스럽다 — chat 쪽 발행부를 만질 때
   같은 방식으로.
