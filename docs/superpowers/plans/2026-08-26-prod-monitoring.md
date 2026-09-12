# 운영 모니터링 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** EC2 운영 환경에 Prometheus/Grafana/Alertmanager/node-exporter 를 올리고 디스코드 알림까지 잇는다.

**Architecture:** 모니터링 4종을 기존 `comatching` 브리지 네트워크에 넣고 호스트에는 Grafana `127.0.0.1:3001` 만 연다. 게이트웨이만 관리 포트를 8081로 분리해 `/actuator/prometheus` 의 외부 노출을 막는다. 호스트 스왑 도입에 대비해 전 컨테이너에 `memswap_limit` 을 명시한다.

**Tech Stack:** Docker Compose, Prometheus v2.54.1, Grafana 11.2.0, Alertmanager v0.27.0, node-exporter v1.8.2, Spring Boot Actuator + Micrometer

**Spec:** `docs/superpowers/specs/2026-08-26-prod-monitoring-design.md`

## Global Constraints

- 운영 컴포즈는 `docker-compose.prod.yml` 하나다. 모니터링용 별도 컴포즈 파일을 만들지 않는다 (로컬용 `docker-compose.monitoring.yml` 은 건드리지 않는다).
- 호스트에 새로 여는 포트는 Grafana `127.0.0.1:3001:3000` 하나뿐이다. Prometheus/Alertmanager/node-exporter 는 `ports` 를 아예 쓰지 않는다.
- 이미지 태그는 로컬 스택과 동일하게 고정: `prom/prometheus:v2.54.1`, `grafana/grafana:11.2.0`, `prom/alertmanager:v0.27.0`, `prom/node-exporter:v1.8.2`.
- mem_limit: prometheus 512m / grafana 256m / alertmanager 128m / node-exporter 64m.
- JVM 6종·redis·모니터링 4종은 `memswap_limit == mem_limit`. kafka 1536m, mongodb 896m.
- 운영 scrape/evaluation interval 은 15s (로컬의 5s 를 복사하지 않는다).
- 커밋은 태스크마다 하되 **푸시는 하지 않는다** (사용자 지시).
- 이 저장소의 주석 문화를 따른다: "무엇"이 아니라 "왜"를 한국어로 적는다.
- 로컬 검증에서 `.env.prod` 가 필요하면 `cp .env.prod.example .env.prod` 로 만든다(.gitignore 에 있어 커밋되지 않는다). 검증 후 지워도 되고 남겨도 된다.

---

### Task 1: 게이트웨이 관리 포트 분리

라우트에 `/actuator/**` 가 없어서 그 경로는 게이트웨이 자신이 응답한다. 8080은 nginx가 TLS를 종단해 넘기는 대상이라, 이대로 prometheus 를 열면 `https://srv.comatching.site/actuator/prometheus` 가 인증 없이 공개된다. 관리 엔드포인트를 8081로 옮기고 호스트에 매핑하지 않는다.

**Files:**
- Modify: `gateway-service/src/main/resources/application-aws.yml` (파일 끝 management 블록, 139행 부근)
- Modify: `docker-compose.prod.yml` (gateway-service 의 healthcheck)

**Interfaces:**
- Produces: 컴포즈 네트워크 안에서 `gateway-service:8081/actuator/prometheus`, `gateway-service:8081/actuator/health`. Task 4 의 스크레이프 대상과 Task 6 의 기동 검증이 이 주소를 쓴다.

- [ ] **Step 1: application-aws.yml 의 management 블록 교체**

현재 블록:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      # 게이트웨이의 8080 은 외부에 열려 있다. always 로 두면 인증 없이
      # DB/Redis 구성요소 상태와 접속 정보 일부가 그대로 드러난다.
      show-details: never
```

다음으로 교체한다:

```yaml
management:
  server:
    # 8080 은 nginx 가 TLS 를 종단해 넘겨주는 포트라, 라우트에 걸리지 않는
    # /actuator/** 를 게이트웨이 자신이 응답하면 그대로 외부에 공개된다.
    # 관리 엔드포인트를 8081 로 옮기고 호스트에 매핑하지 않는다 - 컴포즈
    # 네트워크 안(Prometheus 스크레이프, 헬스체크)에서만 닿는다.
    port: 8081
  endpoints:
    web:
      exposure:
        include: health,prometheus
  endpoint:
    health:
      # 8081 이라도 상세는 숨긴다. DB/Redis 구성요소 상태와 접속 정보
      # 일부가 드러나는 것은 내부라도 이득이 없다.
      show-details: never
```

- [ ] **Step 2: 컴포즈 healthcheck 를 8081 로**

`docker-compose.prod.yml` 의 gateway-service healthcheck 를 수정한다. 이걸 빼먹으면 기동 직후부터 healthcheck 가 영구 실패해 컨테이너가 unhealthy 로 남는다:

```yaml
    healthcheck:
      # 관리 포트가 8081 로 분리됐다 (application-aws.yml 참고).
      test: ["CMD", "curl", "-fsS", "http://localhost:8081/actuator/health"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 90s
```

- [ ] **Step 3: 컴포즈 문법 검증**

```powershell
if (-not (Test-Path .env.prod)) { cp .env.prod.example .env.prod }
docker compose -f docker-compose.prod.yml --env-file .env.prod config -q; echo "exit=$LASTEXITCODE"
```

Expected: `exit=0` (경고는 무시, 에러만 실패)

- [ ] **Step 4: 게이트웨이 테스트가 여전히 통과하는지 확인**

`application-aws.yml` 은 aws 프로파일 전용이라 기본 테스트에 영향이 없어야 정상이다:

```powershell
.\gradlew.bat :gateway-service:test --no-daemon
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add gateway-service/src/main/resources/application-aws.yml docker-compose.prod.yml
git commit -m "feat(monitoring): 게이트웨이 관리 엔드포인트를 8081 로 분리한다"
```

---

### Task 2: 백엔드 5종에 prometheus 노출 추가

user/matching/chat/item/notification 은 게이트웨이 라우트에도 없고 호스트 포트 매핑도 없어 외부 도달 경로가 아예 없다. 포트 분리 없이 `exposure.include` 에 `prometheus` 만 더한다.

**Files:**
- Modify: `user-service/src/main/resources/application-aws.yml`
- Modify: `matching-service/src/main/resources/application-aws.yml`
- Modify: `chat-service/src/main/resources/application-aws.yml`
- Modify: `item-service/src/main/resources/application-aws.yml`
- Modify: `notification/src/main/resources/application-aws.yml`

**Interfaces:**
- Produces: `user-service:9000` / `matching-service:9001` / `chat-service:9003` / `notification:9005` / `item-service:9006` 의 `/actuator/prometheus`. Task 4 의 스크레이프 대상이다.

- [ ] **Step 1: 5개 파일에서 include 한 줄씩 수정**

각 파일의 management 블록에서 아래 한 줄만 바꾼다 (블록 위치는 파일마다 다르다: chat 65행, item 95행, matching 84행, notification 42행, user 53행 부근):

```yaml
        include: health
```

→

```yaml
        # 컨테이너 밖(호스트 포트 매핑·게이트웨이 라우트)으로 나가는 길이
        # 없는 포트라 그대로 연다. Prometheus 가 컴포즈 네트워크 안에서 긁는다.
        include: health,prometheus
```

- [ ] **Step 2: 5개 파일이 전부 바뀌었는지 확인**

```bash
grep -l "health,prometheus" */src/main/resources/application-aws.yml | wc -l
```

Expected: `6` (Task 1 의 게이트웨이 포함)

- [ ] **Step 3: Commit**

```bash
git add */src/main/resources/application-aws.yml
git commit -m "feat(monitoring): aws 프로파일에서 prometheus 엔드포인트를 연다"
```

---

### Task 3: 전 컨테이너에 memswap_limit 명시

docker 는 `mem_limit` 만 주면 memory+swap 합계를 그 2배로 잡는다. 호스트에 스왑을 켜는 순간 컨테이너 9개의 실효 상한이 조용히 두 배가 된다. JVM(full GC 가 스왑 페이지를 EBS 에서 되읽으면 GC 한 번이 수십 초)과 redis(AOF rewrite 의 fork/COW)는 스왑을 아예 못 쓰게 못박는다.

**Files:**
- Modify: `docker-compose.prod.yml` (기존 9개 서비스)

**Interfaces:**
- Produces: 없음 (자기완결). EC2 에서 스왑을 켜는 런북(Task 8)이 이 핀을 전제한다.

- [ ] **Step 1: 9개 서비스에 memswap_limit 추가**

각 서비스의 `mem_limit` 바로 아래에 추가한다. 값 대응표:

| 서비스 | mem_limit (기존) | memswap_limit (추가) |
|---|---|---|
| kafka | 1280m | 1536m |
| mongodb | 768m | 896m |
| redis | 320m | 320m |
| user-service | 768m | 768m |
| matching-service | 640m | 640m |
| chat-service | 576m | 576m |
| item-service | 640m | 640m |
| notification | 576m | 576m |
| gateway-service | 512m | 512m |

JVM 6종과 redis 에는 이 주석을 한 번만(첫 등장인 user-service 에) 단다:

```yaml
    mem_limit: 768m
    # mem_limit 과 같은 값 = 스왑 사용 0. memswap_limit 을 생략하면 docker 가
    # memory+swap 을 mem_limit 의 2배로 잡아서, 호스트에 스왑이 생기는 순간
    # 실효 상한이 조용히 두 배가 된다. JVM 은 full GC 가 힙 전체를 훑으므로
    # 스왑에 밀린 페이지를 EBS 에서 되읽으면 GC 한 번이 수십 초가 된다.
    # 지금처럼 자기 한도에서 OOMKill 로 시끄럽게 죽는 편이 낫다
    # (Dockerfile 의 -XX:+ExitOnOutOfMemoryError 와 같은 철학).
    memswap_limit: 768m
```

kafka 에는:

```yaml
    mem_limit: 1280m
    # 힙(-Xmx1g) 밖 페이지캐시가 잠깐 넘칠 때를 위한 여유 256m. JVM 앱들과
    # 달리 약간의 스왑은 허용하되 무제한(기본값: mem_limit 의 2배)은 막는다.
    memswap_limit: 1536m
```

mongodb 에는:

```yaml
    mem_limit: 768m
    # WiredTiger 캐시(0.25GB) 밖 워킹셋이 잠깐 넘칠 때를 위한 여유 128m.
    memswap_limit: 896m
```

redis 에는:

```yaml
    mem_limit: 320m
    # AOF rewrite 가 fork 하며 copy-on-write 로 페이지를 복제한다. 그 순간
    # 페이지가 스왑에 있으면 지연이 폭발하므로 스왑 사용을 0 으로 못박는다.
    memswap_limit: 320m
```

- [ ] **Step 2: 검증 — 9개 전부 들어갔는지, 값이 맞는지**

```powershell
docker compose -f docker-compose.prod.yml --env-file .env.prod config | Select-String "memswap"
```

Expected: 9줄. `config` 출력은 바이트 단위로 풀리므로 (예: 768m → 805306368) 값 검증은 원본 yml 에서 한다:

```bash
grep -c "memswap_limit" docker-compose.prod.yml
```

Expected: `9`

- [ ] **Step 3: Commit**

```bash
git add docker-compose.prod.yml
git commit -m "feat(monitoring): 호스트 스왑 도입에 대비해 memswap_limit 을 못박는다"
```

---

### Task 4: 운영 Prometheus 설정 + 알림 규칙

로컬 [prometheus.yml](../../../monitoring/prometheus/prometheus.yml)은 `host.docker.internal` 을 보고 5s 간격이라 운영에 못 쓴다. 운영 전용 설정과 알림 규칙을 새로 만든다.

**Files:**
- Create: `monitoring/prometheus/prometheus.prod.yml`
- Create: `monitoring/prometheus/rules/alerts.yml`

**Interfaces:**
- Consumes: Task 1 의 `gateway-service:8081`, Task 2 의 서비스별 `/actuator/prometheus`
- Produces: Task 6 이 이 두 파일(및 rules 디렉터리)을 컨테이너에 마운트한다. Alertmanager 대상 이름은 `alertmanager:9093` (Task 6 의 서비스명과 일치해야 한다).

- [ ] **Step 1: prometheus.prod.yml 작성**

```yaml
# 운영(EC2) 전용. 로컬 prometheus.yml 과 두 가지가 다르다.
#   - 같은 컴포즈 네트워크 안이라 host.docker.internal 이 아니라 컨테이너
#     이름으로 간다.
#   - 15s 간격. 5s 는 짧은 부하 테스트에서 데이터 포인트를 확보하려던
#     값이라 상시 운영에는 과하다(TSDB 쓰기량과 카디널리티가 3배).
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - /etc/prometheus/rules/*.yml

alerting:
  alertmanagers:
    - static_configs:
        - targets: ['alertmanager:9093']

scrape_configs:
  - job_name: comatching-services
    metrics_path: /actuator/prometheus
    static_configs:
      # 게이트웨이만 관리 포트가 8081 이다. 8080 은 nginx 뒤로 외부에
      # 노출되는 포트라 actuator 를 그쪽에 열 수 없다(application-aws.yml).
      - targets: ['gateway-service:8081']
        labels: { service: gateway-service }
      - targets: ['user-service:9000']
        labels: { service: user-service }
      - targets: ['matching-service:9001']
        labels: { service: matching-service }
      - targets: ['chat-service:9003']
        labels: { service: chat-service }
      - targets: ['notification:9005']
        labels: { service: notification }
      - targets: ['item-service:9006']
        labels: { service: item-service }

  - job_name: node
    static_configs:
      - targets: ['node-exporter:9100']

  - job_name: prometheus
    static_configs:
      - targets: ['localhost:9090']
```

- [ ] **Step 2: rules/alerts.yml 작성**

```yaml
# 알림의 기준: "이게 울리면 하던 일을 멈춰야 하나".
# critical = 예(지금 본다), warning = 아니오(오늘 안에 본다).
groups:
  - name: comatching
    rules:
      - alert: ServiceDown
        expr: up{job="comatching-services"} == 0
        for: 2m
        labels: { severity: critical }
        annotations:
          summary: "{{ $labels.service }} 스크레이프 실패 2분"
          description: "컨테이너가 죽었거나 actuator 가 응답하지 않는다. docker compose ps 부터 본다."

      - alert: HighErrorRate
        expr: >
          sum by (service) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
            / sum by (service) (rate(http_server_requests_seconds_count[5m])) > 0.05
        for: 5m
        labels: { severity: critical }
        annotations:
          summary: "{{ $labels.service }} 5xx 비율 5% 초과 5분"
          description: "값: {{ $value | humanizePercentage }}. 해당 서비스 로그부터 본다."

      - alert: JvmHeapHigh
        expr: >
          sum by (service) (jvm_memory_used_bytes{area="heap"})
            / sum by (service) (jvm_memory_max_bytes{area="heap"}) > 0.9
        for: 10m
        labels: { severity: warning }
        annotations:
          summary: "{{ $labels.service }} 힙 90% 초과 10분"
          description: "OOMKill(-XX:+ExitOnOutOfMemoryError 로 즉사·재시작) 전조다."

      - alert: KafkaConsumerLag
        expr: kafka_consumer_fetch_manager_records_lag_max > 1000
        for: 10m
        labels: { severity: warning }
        annotations:
          summary: "컨슈머 lag 1000 초과 10분"
          description: "{{ $labels.service }} 의 컨슈머가 밀리고 있다. DLT 적재 여부도 확인."

      - alert: DiskSpaceLow
        expr: >
          1 - node_filesystem_avail_bytes{mountpoint="/"}
            / node_filesystem_size_bytes{mountpoint="/"} > 0.85
        for: 5m
        labels: { severity: warning }
        annotations:
          summary: "루트 디스크 85% 초과"
          description: "docker image prune, Prometheus retention, 로그부터 의심한다."

      # memswap_limit 핀 때문에 컨테이너는 스왑을 못 쓴다. 스왑이 실제로
      # 쓰인다는 것은 호스트(sshd, docker 데몬 등)가 메모리 압박을 받고
      # 있다는 뜻이다 - mem_limit 합 7,040m / 7.7GiB 예산의 조기 경보.
      - alert: SwapInUse
        expr: node_memory_SwapTotal_bytes - node_memory_SwapFree_bytes > 256 * 1024 * 1024
        for: 10m
        labels: { severity: warning }
        annotations:
          summary: "호스트 스왑 사용 256MB 초과 10분"
          description: "컨테이너 mem_limit 합이 호스트를 압박하고 있다. spec 4.1 의 완화 순서대로."
```

- [ ] **Step 3: promtool 로 검증**

promtool 이 rule_files 글롭을 설정 파일 기준으로 해석하므로, 디렉터리를 `/etc/prometheus` 에 그대로 마운트해야 규칙 파일까지 함께 검증된다:

```powershell
docker run --rm --entrypoint promtool -v "${PWD}\monitoring\prometheus:/etc/prometheus" prom/prometheus:v2.54.1 check config /etc/prometheus/prometheus.prod.yml
```

Expected: `SUCCESS: 6 rules found` 와 `prometheus.prod.yml is valid`

- [ ] **Step 4: Commit**

```bash
git add monitoring/prometheus/prometheus.prod.yml monitoring/prometheus/rules/alerts.yml
git commit -m "feat(monitoring): 운영 스크레이프 설정과 알림 규칙 6종"
```

---

### Task 5: Alertmanager 디스코드 설정

Alertmanager 는 설정 파일에서 환경변수를 확장하지 않는다. 그래서 spec 5.1 의 `INFRA_ALERT_WEBHOOK_URL` 환경변수 방식 대신, `.env.prod` 와 같은 패턴을 쓴다: **예제 파일은 추적하고 실제 파일(웹훅 URL 포함)은 EC2 에만 둔다.** deploy.yml 의 `git reset --hard` 는 추적되지 않는 파일을 건드리지 않으므로 배포에도 살아남는다.

**Files:**
- Create: `monitoring/alertmanager/alertmanager.example.yml`
- Modify: `.gitignore` (실제 파일 제외 규칙)
- Modify: `.env.prod.example` (Grafana 비밀번호 추가 + 웹훅 안내 주석)

**Interfaces:**
- Produces: Task 6 이 `./monitoring/alertmanager/alertmanager.yml` 을 마운트한다 (EC2 에서 example 을 복사해 만드는 파일). `GRAFANA_ADMIN_PASSWORD` 환경변수를 Task 6 의 grafana 서비스가 쓴다.

- [ ] **Step 1: alertmanager.example.yml 작성**

```yaml
# ============================================================
# 이 파일을 alertmanager.yml 로 복사해서 웹훅 URL 을 채운다.
#   cp alertmanager.example.yml alertmanager.yml
# alertmanager.yml 은 .gitignore 에 있다 - 웹훅 URL 자체가 비밀값이다
# (URL 만 알면 누구나 그 채널에 글을 쓸 수 있다).
#
# 웹훅은 DLT_ALERT_WEBHOOK_URL 과 다른 채널로 판다. DLT 알림은 잦고 안
# 급하며, 인프라 알림은 드물고 급하다. 한 채널에 섞으면 잦은 쪽이 드문
# 쪽을 묻어버려 결국 둘 다 안 읽는다.
# ============================================================
route:
  receiver: discord
  group_by: [alertname, service]
  group_wait: 30s
  group_interval: 5m
  # 해결 안 된 알림의 재전송 간격. 너무 짧으면 같은 알림이 도배되어
  # "잦은 쪽이 드문 쪽을 묻는" 문제를 스스로 만든다.
  repeat_interval: 4h

receivers:
  - name: discord
    discord_configs:
      - webhook_url: 'https://discord.com/api/webhooks/CHANGE/ME'
        send_resolved: true
```

- [ ] **Step 2: .gitignore 에 실제 파일 제외**

`.gitignore` 의 `/docs/superpowers/**/.omc/` 규칙 아래에 추가한다:

```
# Alertmanager 실제 설정은 디스코드 웹훅 URL 을 담고 있어 EC2 에만 둔다.
# 예제(alertmanager.example.yml)만 추적한다 - .env.prod 와 같은 패턴.
/monitoring/alertmanager/alertmanager.yml
```

- [ ] **Step 3: .env.prod.example 에 추가**

`# ---------- DLT 적재 알림 ----------` 절 앞에 추가한다:

```
# ---------- 모니터링 ----------
# Grafana 관리자 비밀번호. SSH 터널(127.0.0.1:3001) 너머로만 닿지만
# 익명 접근을 꺼둔 이상 계정은 있어야 한다.
GRAFANA_ADMIN_PASSWORD=

# 인프라 알림(서비스 다운·5xx·힙·디스크)의 디스코드 웹훅은 환경변수가
# 아니라 monitoring/alertmanager/alertmanager.yml 에 넣는다 - Alertmanager 가
# 설정 파일에서 환경변수를 확장하지 못한다. 그 파일은 .gitignore 에 있다.
# DLT_ALERT_WEBHOOK_URL 과 반드시 다른 채널로 팔 것(아래 DLT 절 참고).
```

- [ ] **Step 4: amtool 로 검증**

```powershell
docker run --rm --entrypoint amtool -v "${PWD}\monitoring\alertmanager:/work" prom/alertmanager:v0.27.0 check-config /work/alertmanager.example.yml
```

Expected: `Found: 1 receivers` 및 에러 없음. `discord_configs` 를 파서가 거부하면(버전 문제) spec 8장의 대체 경로(webhook_configs)로 전환하고 사용자에게 보고한다.

- [ ] **Step 5: Commit**

```bash
git add monitoring/alertmanager/alertmanager.example.yml .gitignore .env.prod.example
git commit -m "feat(monitoring): 인프라 알림용 Alertmanager 디스코드 설정"
```

---

### Task 6: 컴포즈에 모니터링 4종 + Grafana 운영 프로비저닝

**Files:**
- Create: `monitoring/grafana/provisioning-prod/datasources/prometheus.yml`
- Create: `monitoring/grafana/provisioning-prod/dashboards/dashboards.yml`
- Modify: `docker-compose.prod.yml` (서비스 4개 + 볼륨 2개 추가)

**Interfaces:**
- Consumes: Task 4 의 `prometheus.prod.yml`·`rules/`, Task 5 의 `alertmanager.yml`(EC2 에서 생성)·`GRAFANA_ADMIN_PASSWORD`
- Produces: `prometheus:9090`, `alertmanager:9093`, `node-exporter:9100`, 호스트의 `127.0.0.1:3001`(Grafana)

- [ ] **Step 1: Grafana 운영 데이터소스 프로비저닝**

로컬 프로비저닝 디렉터리를 그대로 마운트하면 안 된다 — influxdb.yml(운영에 InfluxDB 없음)과 mysql.yml(`host.docker.internal:3307` 를 봄)이 기동마다 에러를 만든다. 운영 전용 디렉터리를 만든다.

`monitoring/grafana/provisioning-prod/datasources/prometheus.yml`:

```yaml
# 운영 전용 프로비저닝. 로컬(provisioning/)과 분리한 이유:
#   - influxdb 데이터소스는 JMeter 전용이라 운영에 없다.
#   - mysql 데이터소스는 host.docker.internal:3307 을 봐서 운영에서 깨진다.
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    uid: prometheus                  # 대시보드 JSON 이 이 uid 로 참조한다
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
    jsonData:
      timeInterval: 15s              # prometheus.prod.yml 의 scrape_interval 과 일치
```

- [ ] **Step 2: Grafana 대시보드 프로비저닝**

`monitoring/grafana/provisioning-prod/dashboards/dashboards.yml`:

```yaml
apiVersion: 1

providers:
  - name: comatching
    orgId: 1
    folder: Comatching
    type: file
    disableDeletion: false
    updateIntervalSeconds: 30
    allowUiUpdates: true
    options:
      path: /var/lib/grafana/dashboards
      foldersFromFilesStructure: false
```

- [ ] **Step 3: 컴포즈에 서비스 4개 추가**

`docker-compose.prod.yml` 의 gateway-service 아래에 추가한다. 대시보드는 `comatching-baseline.json` 만 마운트한다 — loadtest 는 influxdb 를, mysql-queries 는 mysql 데이터소스를 참조해 운영에서 패널이 깨진다.

```yaml
  # ---------- 모니터링 ----------
  # 넷 다 comatching 네트워크 안이다. 호스트에 여는 건 grafana 의
  # 127.0.0.1:3001 하나뿐이고, 보는 방법은 SSH 터널이다:
  #   ssh -L 3001:localhost:3001 <user>@<EC2>

  prometheus:
    image: prom/prometheus:v2.54.1
    container_name: comatching-prometheus
    restart: unless-stopped
    networks: [comatching]
    volumes:
      - ./monitoring/prometheus/prometheus.prod.yml:/etc/prometheus/prometheus.yml:ro
      - ./monitoring/prometheus/rules:/etc/prometheus/rules:ro
      - prometheus-data:/prometheus
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.path=/prometheus
      # 시간과 크기 중 먼저 걸리는 쪽이 이긴다. 디스크가 차서 죽는 것보다
      # 오래된 데이터를 버리는 게 낫다.
      - --storage.tsdb.retention.time=15d
      - --storage.tsdb.retention.size=2GB
    mem_limit: 512m
    memswap_limit: 512m
    healthcheck:
      test: ["CMD", "wget", "-q", "--spider", "http://localhost:9090/-/healthy"]
      interval: 15s
      timeout: 5s
      retries: 10
    logging: *logging

  alertmanager:
    image: prom/alertmanager:v0.27.0
    container_name: comatching-alertmanager
    restart: unless-stopped
    networks: [comatching]
    volumes:
      # 실제 파일은 EC2 에서 example 을 복사해 만든다(웹훅 URL 이 비밀값).
      # 없으면 이 마운트가 실패하므로, 만들기 전에는 이 서비스가 뜨지 않는다.
      - ./monitoring/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro
    mem_limit: 128m
    memswap_limit: 128m
    logging: *logging

  grafana:
    image: grafana/grafana:11.2.0
    container_name: comatching-grafana
    restart: unless-stopped
    networks: [comatching]
    # 루프백에만 바인딩한다. 게이트웨이 8080 과 같은 이유 - 보는 방법은
    # SSH 터널뿐이고, 외부에 열면 보안그룹 한 겹이 유일한 방어선이 된다.
    ports:
      - "127.0.0.1:3001:3000"
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD}
      # 로컬 스택은 익명 Viewer 를 켜뒀지만 운영은 터널 너머라도 계정을
      # 요구한다. 터널 포트를 실수로 0.0.0.0 에 열었을 때의 마지막 겹이다.
      GF_AUTH_ANONYMOUS_ENABLED: "false"
      GF_USERS_DEFAULT_THEME: dark
    volumes:
      - ./monitoring/grafana/provisioning-prod:/etc/grafana/provisioning:ro
      # baseline 만 올린다. loadtest 는 influxdb, mysql-queries 는 mysql
      # 데이터소스를 참조해 운영에서 패널이 깨진다.
      - ./monitoring/grafana/dashboards/comatching-baseline.json:/var/lib/grafana/dashboards/comatching-baseline.json:ro
      - grafana-data:/var/lib/grafana
    depends_on:
      - prometheus
    mem_limit: 256m
    memswap_limit: 256m
    logging: *logging

  node-exporter:
    image: prom/node-exporter:v1.8.2
    container_name: comatching-node-exporter
    restart: unless-stopped
    networks: [comatching]
    # 호스트 지표(CPU·메모리·디스크·스왑)를 읽어야 해서 이 컨테이너만
    # 예외적으로 호스트 네임스페이스를 본다. 전부 읽기 전용이다.
    pid: host
    volumes:
      - /proc:/host/proc:ro
      - /sys:/host/sys:ro
      - /:/rootfs:ro
    command:
      - --path.procfs=/host/proc
      - --path.sysfs=/host/sys
      - --path.rootfs=/rootfs
      - --collector.filesystem.mount-points-exclude=^/(sys|proc|dev|host|var/lib/docker/.+)($$|/)
    mem_limit: 64m
    memswap_limit: 64m
    logging: *logging
```

볼륨 절에 추가:

```yaml
volumes:
  kafka-data:
  mongo-data:
  redis-data:
  prometheus-data:
  grafana-data:
```

- [ ] **Step 4: 컴포즈 문법 검증**

```powershell
docker compose -f docker-compose.prod.yml --env-file .env.prod config -q; echo "exit=$LASTEXITCODE"
```

Expected: `exit=0`

- [ ] **Step 5: 로컬에서 모니터링 3종만 실기동 스모크**

alertmanager 는 실제 설정 파일이 없으면 마운트가 실패하므로, 로컬에서만 example 을 복사해 흉내낸다:

```powershell
cp monitoring/alertmanager/alertmanager.example.yml monitoring/alertmanager/alertmanager.yml
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d prometheus alertmanager node-exporter
Start-Sleep -Seconds 10
docker compose -f docker-compose.prod.yml --env-file .env.prod ps
```

Expected: 셋 다 Up (prometheus 는 healthy). 앱 서비스는 띄우지 않으므로 스크레이프 대상은 down 으로 보이는 게 정상이다.

```powershell
docker exec comatching-prometheus wget -qO- http://localhost:9090/api/v1/rules | Select-String "ServiceDown"
```

Expected: ServiceDown 규칙이 로드되어 있다

- [ ] **Step 6: 정리**

```powershell
docker compose -f docker-compose.prod.yml --env-file .env.prod down
rm monitoring/alertmanager/alertmanager.yml
rm .env.prod
```

- [ ] **Step 7: Commit**

```bash
git add monitoring/grafana/provisioning-prod docker-compose.prod.yml
git commit -m "feat(monitoring): 운영 컴포즈에 모니터링 4종을 올린다"
```

---

### Task 7: 부하 도구의 운영 대응 (HTTPS + RDS)

부하 테스트(spec 7장)는 모니터링이 뜬 뒤의 별도 작업이지만, 도구 수정은 코드라 지금 같이 커밋한다. 두 가지: run.sh 가 http 를 하드코딩해서 nginx 443 으로 못 가고, load_seed.sh 는 `docker exec` + 서버사이드 `LOAD DATA INFILE` 이라 RDS 에서 안 돈다.

**Files:**
- Modify: `tools/perf/jmeter/run.sh:31-33,50` (SCHEME 도입)
- Modify: `tools/perf/jmeter/S1-participants.jmx:67` (protocol 을 프로퍼티로)
- Create: `tools/perf/seed/load_seed_rds.sh` (로컬용 load_seed.sh 는 건드리지 않는다)

**Interfaces:**
- Consumes: 없음
- Produces: `SCHEME=https HOST=srv.comatching.site PORT=443 ./run.sh` 실행 계약. `load_seed_rds.sh` 는 `RDS_ENDPOINT`/`RDS_USERNAME`/`RDS_PASSWORD` 환경변수(.env.prod 와 동일한 이름)를 읽는다.

- [ ] **Step 1: run.sh 에 SCHEME 도입**

31~33행의 변수 선언에 한 줄 추가:

```bash
HOST="${HOST:-localhost}"
PORT="${PORT:-8080}"
# 운영은 nginx 443 뒤라 https 로 가야 한다. 게이트웨이 8080 은 루프백
# 바인딩이라 외부에서 직접 닿지 않는다.
SCHEME="${SCHEME:-http}"
INFLUX_HOST="${INFLUX_HOST:-localhost}"
```

50행의 사전 점검 curl 을 수정:

```bash
code=$(curl -s -o /dev/null -w '%{http_code}' "$SCHEME://$HOST:$PORT/api/auth/participants" || echo 000)
```

114행의 JMeter 인자에 `-Jscheme` 추가:

```bash
  -Jhost="$HOST" -Jport="$PORT" -Jscheme="$SCHEME" -JinfluxHost="$INFLUX_HOST" \
```

- [ ] **Step 2: S1 JMX 의 protocol 을 프로퍼티로**

`S1-participants.jmx` 67행:

```xml
<stringProp name="HTTPSampler.protocol">http</stringProp>
```

→

```xml
<stringProp name="HTTPSampler.protocol">${__P(scheme,http)}</stringProp>
```

- [ ] **Step 3: load_seed_rds.sh 작성**

```bash
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
  mysql -h "$RDS_ENDPOINT" -u "$RDS_USERNAME" -p"$RDS_PASSWORD" \
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
```

- [ ] **Step 4: 문법 검증**

```bash
bash -n tools/perf/jmeter/run.sh && bash -n tools/perf/seed/load_seed_rds.sh && echo OK
```

Expected: `OK`

- [ ] **Step 5: 로컬 스모크 — SCHEME 기본값이 기존 동작을 보존하는지**

```bash
grep -n "SCHEME" tools/perf/jmeter/run.sh && grep -n "__P(scheme" tools/perf/jmeter/S1-participants.jmx
```

Expected: run.sh 3곳(선언·curl·-Jscheme), JMX 1곳. 기본값이 둘 다 `http` 라서 로컬 사용법은 그대로다.

- [ ] **Step 6: Commit**

주의: `.gitignore` 에 `tools` 줄이 작업 트리에 있다면 `load_seed_rds.sh` 가 무시될 수 있다. `git check-ignore` 로 확인하고, 무시되면 사용자에게 보고한다(강제 추가하지 않는다).

```bash
git check-ignore tools/perf/seed/load_seed_rds.sh || echo "추적 가능"
git add tools/perf/jmeter/run.sh tools/perf/jmeter/S1-participants.jmx tools/perf/seed/load_seed_rds.sh
git commit -m "feat(perf): 부하 도구의 운영 대응 - HTTPS 스킴과 RDS 시드 적재"
```

---

### Task 8: EC2 배포 런북

코드가 아니라 EC2 에서 사람이 순서대로 치는 명령의 기록이다. spec 의 절차(4.2 스왑, 7.2 부하, 7.3 초기화)를 한 문서로 모아 순서를 고정한다.

**Files:**
- Create: `docs/superpowers/runbooks/2026-08-26-prod-monitoring-rollout.md`

**Interfaces:**
- Consumes: Task 1~7 의 산출물 전부
- Produces: 없음 (사람이 읽는 문서)

- [ ] **Step 1: 런북 작성**

````markdown
# 운영 모니터링 롤아웃 런북

설계: `docs/superpowers/specs/2026-08-26-prod-monitoring-design.md`
전부 EC2(43.200.211.135)에 SSH 로 들어가서 순서대로 실행한다.

## 1. 사전 확인

```bash
free -h; swapon --show; df -h /
stat -fc %T /sys/fs/cgroup    # cgroup2fs 여야 한다
docker info 2>/dev/null | grep -i "no swap limit" || echo "swap limit 지원 OK"
```

마지막 줄이 "swap limit 지원 OK" 가 아니면 **여기서 멈춘다** —
memswap_limit 이 조용히 무시되어 스왑을 켜면 안 되는 상태다.

## 2. 스왑 2GB

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile \
  && sudo mkswap /swapfile && sudo swapon /swapfile
grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=1' | sudo tee /etc/sysctl.d/99-swap.conf && sudo sysctl --system
free -h    # Swap 2.0Gi 확인
```

## 3. 베이스라인 실측 (모니터링 올리기 전)

```bash
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}" | tee ~/baseline-$(date +%F).txt
```

## 4. 설정 준비

```bash
cd ~/comatching && git fetch origin main && git reset --hard origin/main
cp monitoring/alertmanager/alertmanager.example.yml monitoring/alertmanager/alertmanager.yml
vi monitoring/alertmanager/alertmanager.yml   # 웹훅 URL 교체 (DLT 와 다른 채널!)
vi .env.prod                                  # GRAFANA_ADMIN_PASSWORD= 채움
```

## 5. 기동

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod pull
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --no-build
docker compose -f docker-compose.prod.yml --env-file .env.prod ps
```

게이트웨이 관리 포트가 8081 로 바뀌었으므로 앱 이미지도 이 커밋으로
빌드된 것이어야 한다(CI 배포를 먼저 태우거나 --build).

## 6. 검증

```bash
# 스크레이프 6대상 전부 up=1 이어야 한다
docker exec comatching-prometheus wget -qO- 'http://localhost:9090/api/v1/query?query=up' | grep -o '"service":"[^"]*","value":\[[^]]*\]' 

# 외부에서 actuator 가 안 열리는지 (401/404 여야 하고 200 이면 사고다)
curl -s -o /dev/null -w '%{http_code}\n' https://srv.comatching.site/actuator/prometheus

# 알림 경로: Alertmanager 가 디스코드로 쏘는지 임시 서비스 하나를 죽여 확인
docker stop comatching-notification && sleep 180   # ServiceDown(2m) 발화 대기
docker start comatching-notification               # 디스코드에 알림+해제 확인
```

로컬 PC 에서:

```bash
ssh -L 3001:localhost:3001 <user>@43.200.211.135
# 브라우저 http://localhost:3001 → admin / GRAFANA_ADMIN_PASSWORD
```

## 7. 외부 업타임 감시

UptimeRobot 등에서 웹으로 등록 (EC2 작업 없음):
- URL: `https://srv.comatching.site/actuator/health`, 주기 5분
- 알림: 인프라 디스코드 채널

## 8. 부하 테스트 (spec 7.2)

```bash
# RDS 파라미터 그룹에서 local_infile=1 적용 후:
set -a; . ~/comatching/.env.prod; set +a
cd ~/comatching/tools/perf/seed && ./load_seed_rds.sh          # 더미 10만
cd ../tokens && python3 generate_tokens.py --env ~/comatching/.env.prod

# 부하 중 실측 기록
docker stats --format "{{.Name}},{{.MemUsage}},{{.MemPerc}},{{.CPUPerc}}" >> ~/stats-$(date +%F-%H%M).csv
```

부하는 로컬 PC 에서: `SCHEME=https HOST=srv.comatching.site PORT=443 ./run.sh`

## 9. 초기화 (런칭 전 1회)

```bash
cd ~/comatching
docker compose -f docker-compose.prod.yml --env-file .env.prod down -v   # kafka/mongo/redis 볼륨 삭제
sed -i 's/^JPA_DDL_AUTO=.*/JPA_DDL_AUTO=create/' .env.prod
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --no-build

# 전부 healthy 확인 후 ★반드시★ 즉시 되돌린다. create 인 채로 재기동하면
# 실서비스 데이터가 전부 날아간다 (CI 배포와 restart 정책이 재기동을 만든다).
sed -i 's/^JPA_DDL_AUTO=.*/JPA_DDL_AUTO=validate/' .env.prod && grep JPA_DDL_AUTO .env.prod
```
````

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/runbooks/2026-08-26-prod-monitoring-rollout.md
git commit -m "docs(monitoring): EC2 롤아웃 런북"
```

---

## Self-Review 기록

- **Spec coverage:** 3.1→Task 6, 3.2→Task 1, 3.3→Task 4, 4.2→Task 8(§1-2), 4.3→Task 3, 5장→Task 4·5, 6장→Task 8(§7), 7.1→Task 7, 7.2~7.3→Task 8(§8-9), 9장 구현 순서 = Task 1~7 순서와 일치.
- **Spec 과의 의도적 편차 1건:** spec 5.1 은 `INFRA_ALERT_WEBHOOK_URL` 환경변수를 제안했으나 Alertmanager 가 설정 파일에서 환경변수를 확장하지 못한다. `.env.prod` 패턴(example 추적 + 실제 파일 gitignore)으로 대체했다 — Task 5 서두에 근거 명시.
- **미검증 리스크(스펙 8장)와의 대응:** `discord_configs` → Task 5 Step 4 의 amtool 검증에서 걸러짐. `kafka_consumer_fetch_manager_records_lag_max` → EC2 기동 후 런북 6절 검증에서 확인, 없으면 규칙만 제거.
- **Type consistency:** 서비스명 `alertmanager`(Task 4 의 타깃 = Task 6 의 서비스명), 볼륨 `prometheus-data`/`grafana-data`, 환경변수 `GRAFANA_ADMIN_PASSWORD`(Task 5 정의 = Task 6 사용), 실행 계약 `SCHEME`(Task 7 내 3곳) 일치 확인.
