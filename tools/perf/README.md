# 부하 테스트 환경 가이드

측정 → 문제 발견 → 개선 → 재측정 루프를 돌리기 위한 도구 모음이다.

**규모 가정**: 누적 가입자 10만, 피크 CCU 5만, think-time 30초 → 목표 약 1,667 RPS.
목표 달성 여부가 아니라 **꺾이는 지점(knee)** 을 찾는 게 목적이다.

```
tools/perf/
├── seed/
│   ├── generate_seed.py       # TSV 생성 (균일 분포)
│   ├── load_seed.sh           # MySQL 적재
│   └── out/                   # 생성물 (git 무시)
├── tokens/
│   ├── generate_tokens.py     # JWT 발급
│   └── tokens.csv             # JMeter 주입용 (git 무시)
├── jmeter/
│   ├── S1-participants.jmx    # 회차 1 시나리오
│   ├── run.sh                 # 점검 → 실행 → 요약 → 리포트
│   ├── summarize.py           # 계단별 요약 + knee 자동 판정
│   └── results/               # 회차별 결과 (git 무시)
└── cpu_sampler.sh             # 호스트 맥 CPU 기록 (측정 유효성 판정용)
```

**실행 순서**: 1 시드 생성 → 2 적재 → 3 토큰 → 4 JMeter·InfluxDB 준비 → 5 회차 실행 → 6 결과 해석

---

## 사전 준비 (선택) — InnoDB 버퍼 풀

MySQL 기본 `innodb_buffer_pool_size` 는 128MB다. 10만 행 + 인덱스가 이 안에 다 안 들어가면 **측정하려는 게 쿼리 비용이 아니라 디스크 I/O 가 된다.** 회차마다 캐시 적중률이 달라져 재현성도 떨어진다.

`docker-compose.local.yml` 의 mysql `command:` 에 한 줄 추가:

```yaml
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
      - --innodb-buffer-pool-size=1G
```

적용하려면 컨테이너 재생성이 필요하다. `mysql-data` 는 named volume 이라 데이터는 남는다.

```bash
docker compose -f docker-compose.local.yml up -d mysql
```

**이건 결정 사항이다.** 안 바꾸고 진행해도 되지만, 그 경우 "디스크 I/O 가 섞여 있다"는 걸 회차 기록에 남겨야 한다.

---

## 1단계 — 시드 데이터 생성

```bash
cd /Users/seunghwan/Documents/Comatching5_BE/tools/perf/seed && python3 generate_seed.py
```

10만 명 기준 수 초면 끝난다. 처음이라면 **1만 명으로 먼저 돌려보는 걸 권한다** — 전체 절차가 도는지 빠르게 확인하고 나서 10만으로 가는 게 낫다.

```bash
cd /Users/seunghwan/Documents/Comatching5_BE/tools/perf/seed && python3 generate_seed.py --count 10000
```

### 무엇을 볼 것인가

출력 끝에 분포 리포트가 나온다.

```
분포 확인 (편차가 1 이하여야 정상)
  gender               종류   2개  최소 50,000  최대 50,000  편차 0
  age                  종류   7개  최소 14,285  최대 14,286  편차 1
  ...
  gender x major       칸  38개  최소 2,631  최대 2,632  편차 1
```

판정 기준이 두 가지로 나뉜다. **섞지 말 것.**

**주변 분포(gender, age, mbti, ... 각각)** → **편차 1 이하**여야 한다. 정확한 개수를 만들어 셔플하므로 나누어떨어지지 않는 나머지만큼만 차이가 난다.

**교차표(`gender x major`)** → **편차가 아니라 기대값 대비 비율로 본다. 10% 이내면 정상.**

두 속성을 독립적으로 셔플하면 교차표는 필연적으로 흔들린다. 100,000 ÷ 38칸 = 기대값 2,632, 표준편차 약 26이고, 38칸의 최대-최소 폭은 보통 4~5 표준편차(100~130)다. 편차 133이면 2,632 대비 5% — 정상이다.

오히려 **교차표가 편차 0으로 딱 맞으면 그게 이상하다.** 두 속성을 독립적으로 뽑은 게 아니라 인위적으로 짝지었다는 뜻이고, 그건 현실 데이터에서 나올 수 없는 구조다.

10%를 넘어가면 그때 성별과 학과가 상관됐는지 의심한다. 상관이 생기면 한쪽 성별의 후보 풀이 달라져서, 매칭 성능 차이가 개선 효과인지 데이터 편중인지 구분되지 않는다.

### 왜 균일하게 하나

편중이 있으면 **성능 변화가 개선 때문인지 데이터 우연인지 알 수 없다.** 균일하면 후보 수가 예측 가능해져서 커서 루프 비용이 결정적(deterministic)이 된다.

대신 현실과는 다르다. 실제로는 특정 학과·MBTI 에 몰린다. 그 편중이 만드는 문제(작업순서 md 2-1 "후보 풀 편중과 고갈")는 **별도 회차**에서 편중 분포로 다시 다룬다. 1차는 측정 가능성이 우선이다.

---

## 2단계 — MySQL 적재

```bash
cd /Users/seunghwan/Documents/Comatching5_BE/tools/perf/seed && ./load_seed.sh
```

### 무엇을 볼 것인가

스크립트가 끝에 세 가지를 자동으로 검사한다.

| 검사 | 정상 | 비정상이면 |
|---|---|---|
| 테이블별 행 수 | 생성한 수와 일치 | LOAD DATA 가 일부만 먹었다. warning 확인 |
| **`age_is_null`** | **0** | **0 이 아니면 매칭이 전부 실패한다.** `matching_candidate.age` 는 Kafka 이벤트로만 채워지는 컬럼이라 직접 넣어야 한다 |
| `gender` 분포 | 5:5 | 시드 생성 문제 |
| `active_users` | 시드 수 + 실계정 | 부하 대상 쿼리(`participants`)가 세는 값과 동일 |

### 자주 걸리는 실패

**`The MySQL server is running with the --secure-file-priv option`**
→ 스크립트가 `@@secure_file_priv` 를 읽어 자동으로 맞추지만, 값이 비어 있거나 NULL 이면 실패한다. 출력 상단의 `secure_file_priv = ...` 를 확인하라.

**`Can't get stat of ... (Errcode: 13 - Permission denied)`**
→ `docker cp` 가 root 소유로 넣어서 mysqld(mysql 유저)가 못 읽는 경우다. 스크립트가 `chown` 을 하지만 실패했을 수 있다.

**행 수가 적게 들어감**
→ TSV 컬럼 수와 `LOAD DATA` 컬럼 목록이 어긋난 것이다. `SHOW WARNINGS;` 로 확인한다.

### 재실행 가능하다

`user-service` 와 `item-service` 는 `ddl-auto: create` 라 **재기동할 때마다 스키마가 날아간다.** 이 스크립트는 적재 전에 기존 시드를 지우므로 언제 다시 돌려도 같은 상태가 된다. 서비스를 재기동했으면 2단계를 다시 돌리면 된다.

---

## 3단계 — JWT 토큰 발급

```bash
cd /Users/seunghwan/Documents/Comatching5_BE/tools/perf/tokens && python3 generate_tokens.py
```

### 반드시 검증하고 넘어갈 것

스크립트가 마지막에 검증 명령을 출력한다. 그대로 실행하면 된다.

```bash
cd /Users/seunghwan/Documents/Comatching5_BE/tools/perf/tokens && TOKEN=$(head -1 tokens.csv | cut -d, -f2) && curl -s -o /dev/null -w '%{http_code}\n' -b "accessToken=$TOKEN" http://localhost:8080/api/matching/history
```

**200 이 나와야 한다.** 여기서 401 이면 JMeter 시나리오 전체가 무용지물이므로 지금 잡아야 한다.

| 응답 | 원인 |
|---|---|
| `200` | 정상 |
| `401` | `jti` 누락 / `JWT_SECRET` Base64 디코드 문제 / 만료 |
| `000` | 게이트웨이가 안 떠 있음 |

### 알아둘 것 두 가지

**인증은 쿠키다.** `Authorization: Bearer` 는 게이트웨이가 읽지 않는다 (`AuthorizationHeaderFilter.java:109` 가 `accessToken` 쿠키만 본다). JMeter 에서는 **HTTP Cookie Manager** 를 반드시 넣어야 한다.

**VU 마다 다른 memberId 를 써야 한다.** 아래 세 개의 Redisson 락이 전부 memberId 단위다.

```
MATCHING_REQUEST:<memberId>          leaseTime 15초
item:inventory:<memberId>:<type>     leaseTime 10초
order:pending:<memberId>
```

계정을 공유하면 락 대기 5초 후 429(`TOO_MANY_REQUEST`)가 쏟아진다. 그러면 측정되는 건 시스템 처리량이 아니라 락 경합이다.

---

## 4단계 — JMeter 설치와 InfluxDB 기동

```bash
brew install jmeter
```

```bash
cd /Users/seunghwan/Documents/Comatching5_BE && docker compose -f docker-compose.monitoring.yml up -d influxdb grafana
```

InfluxDB 는 **JMeter 가 스스로 잰 클라이언트 측 지표**를 받는다. Prometheus 만으로는 부족한 이유가 있다. Prometheus 에 들어오는 건 서버가 자기 시계로 잰 시간이라, 요청이 커널 백로그나 JMeter 자신의 스레드 큐에서 기다린 시간이 빠져 있다. 단일 맥에서는 부하 생성기와 측정 대상이 같은 CPU 를 나눠 쓰기 때문에 이 누락분이 커질 수 있고, **클라이언트 p95 와 서버 p95 의 차이(gap)가 곧 "이 회차를 믿어도 되는가"의 판단 근거**가 된다.

확인:

```bash
curl -s -o /dev/null -w 'influx %{http_code}\n' http://localhost:8086/ping && open http://localhost:3001/d/comatching-loadtest
```

`/ping` 은 **204** 가 정상이다(200 아님). Grafana 에서 `Comatching` 폴더에 **Load Test** 대시보드가 보이면 프로비저닝 성공이다.

---

## 5단계 — 회차 1 실행

```bash
cd /Users/seunghwan/Documents/Comatching5_BE/tools/perf/jmeter && ./run.sh
```

### 무엇이 도는가

| 구간 | 부하 | 시간 |
|---|---|---|
| 워밍업 | 20 RPS | 60초 (집계 제외) |
| 계단 1~5 | 50 → 100 → 200 → 400 → 800 RPS | 각 120초 |

**총 11분.** 도는 동안 맥에서 다른 무거운 작업을 하지 말 것 — 호스트 CPU 가 측정 유효성 기준이다.

`run.sh` 가 하는 일은 다섯 가지다. 사전 점검 → CPU 샘플러 백그라운드 기동 → JMeter 논-GUI 실행 → 계단별 요약 + knee 자동 판정 → HTML 리포트. 결과는 전부 `results/S1-participants-<타임스탬프>/` 한 디렉터리에 모인다.

### 사전 점검에서 걸리면

| 메시지 | 조치 |
|---|---|
| `jmeter 가 없습니다` | `brew install jmeter` |
| `게이트웨이 응답 000` | `./run-local.sh` 로 서비스 기동 |
| `InfluxDB 응답 없음` | `docker compose -f docker-compose.monitoring.yml up -d influxdb` |
| `ulimit -n = 256` | 경고만 나고 진행된다. 4~5계단에서 `Too many open files` 가 뜨면 이게 원인이다 — **서버 문제로 오독하기 쉬우니 반드시 기억할 것** |

---

## 6단계 — 결과 읽기

### 자동으로 나오는 것

`summarize.py` 가 계단별 표와 knee 판정을 찍는다. 판정 기준은 **결정 A7** 을 코드에 박아둔 것이다.

```
p95 > 500ms   또는   에러율 > 1%   또는   부하 2배에 처리량 1.2배 미만
```

셋 중 **먼저 걸리는 계단**이 knee 다. 눈으로 그래프 보고 사후에 기준을 갖다 붙이면 회차마다 판정이 달라지므로 코드로 고정했다.

### 표에서 볼 곳

| 열 | 의미 |
|---|---|
| **달성** | 목표 RPS 대비 실제. **100% 에서 멀어지면 그 자체가 신호다.** 서버가 못 받아준 것일 수도 있고, JMeter 스레드가 부족한 것일 수도 있다. 이 둘의 구분은 아래 CPU·gap 으로 한다 |
| **p50 vs p99** | p50 은 평평한데 p99 만 치솟으면 "대부분 멀쩡하고 일부만 죽는" 상태다. GC, 락 대기, 커넥션 풀 큐 같은 **간헐적** 경합의 전형이다. 평균만 보면 절대 안 보인다 |
| **max** | 워밍업 구간이 아닌데 max 가 p99 의 몇 배면 단발성 스톨(GC full pause, 커넥션 재수립)이 있었다는 뜻 |

### 반드시 함께 봐야 하는 것 세 가지

숫자만으로는 **원인을 알 수 없다.** p95 가 올랐다는 사실과 왜 올랐는지는 다른 문제다.

**① 호스트 CPU (`cpu.csv` 요약)** — p95 가 70% 를 넘으면 **그 회차는 폐기**다. 부하기와 대상이 CPU 를 다퉜다는 뜻이라, 측정된 지연이 서버 탓인지 스케줄링 탓인지 분리되지 않는다.

**② 클라 p95 vs 서버 p95 gap** (Load Test 대시보드 ① 패널) — 클라이언트만 치솟고 서버는 평평하면 병목은 서버가 아니다. 이 대조 없이 단일 맥 측정을 신뢰하면 안 된다.

**③ 어느 자원이 먼저 찼는가** (③ 행 패널) — p95 상승과 **동시에** Hikari `pending` 이 0을 벗어났으면 DB(또는 풀 크기 10), GC pause 가 올랐으면 메모리, 둘 다 평평한데 느려졌으면 쿼리 자체다. 회차 1의 가설은 **세 번째**다 — `members(role, status)` 인덱스가 없어서 요청마다 10만 행 COUNT 풀스캔이 도는 것.

### 대시보드 패널이 비어 있으면

InfluxDB 스키마가 예상과 다를 수 있다. 실제 스키마를 직접 본다:

```bash
docker exec -it comatching-influxdb influx -database jmeter -execute 'SHOW FIELD KEYS FROM jmeter; SHOW TAG KEYS FROM jmeter; SHOW TAG VALUES FROM jmeter WITH KEY = "statut"'
```

패널 쿼리는 필드 `pct95.0` / `count`, 태그 `application` `transaction` `statut(ok|ko|all)` 를 가정하고 있다. 다르면 대시보드 JSON 의 쿼리 문자열만 고치면 된다.

### 기록

회차가 끝나면 `docs/perf-log.md` 에 남긴다. 이게 최종 포트폴리오 산출물이다.

```
## 회차 N — <시나리오> / <가설>
측정 조건: 데이터 10만, 계단 50~800 RPS ×120초, 워밍업 60초 제외, 호스트 CPU p95 %
결과:     계단별 p50/p90/p95/p99/max, 달성률, 에러율, knee 위치
관측:     어느 지표가 먼저 무너졌는지
가설:     병목이 A라고 판단한 근거
결정:     선택지와 고른 이유   ← 핵심
적용:     커밋 해시
재측정:   개선 폭
남은 문제:
```

---

## 부록 — 회차 간 리셋

읽기 전용 시나리오(S1, S2)는 리셋이 필요 없다. **매칭 시나리오(S3, S4)는 회차마다 리셋해야 한다.**

2회차를 오염시키는 상태는 둘이다.

| 상태 | 영향 |
|---|---|
| `matching_history` 행 | ① 후보 풀이 줄어 `NO_MATCHING_CANDIDATE` 증가 ② `pair_key` 충돌 증가 ③ `NOT IN` 리스트가 길어져 쿼리가 느려짐 |
| `item.quantity` 감소 | 티켓이 떨어지면 매칭이 400 으로 **조기 실패** → 후보 검색을 안 타니 **응답이 빨라져 p95 가 거짓으로 좋아진다.** 가장 위험한 왜곡이다 |

그리고 **`matching_history` 를 TRUNCATE 하면 AUTO_INCREMENT 가 1 로 리셋**되는데, Mongo `chat_rooms.matchingId` 에 유니크 인덱스가 있어서 기존 문서와 충돌한다. 그러면 Kafka 컨슈머가 무한 재시도하며 lag 이 쌓인다. **반드시 세트로 지워야 한다.**

리셋 스크립트는 매칭 회차에 들어갈 때 만든다.

### 재현되지 않는 것

`MatchingProcessor.selectRandomCandidate()` 가 `Math.random()` 을 쓴다 (`MatchingProcessor.java:190`). 상태를 완벽히 리셋해도 **매칭 상대는 재현되지 않는다.** 재현 대상은 **지연 / 처리량 / 에러율** 로 한정한다.
