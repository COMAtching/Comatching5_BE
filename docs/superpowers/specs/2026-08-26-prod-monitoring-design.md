# 운영 모니터링 도입 설계

- 작성일: 2026-08-26
- 대상: EC2 단일 인스턴스(t3.large, ap-northeast-2) 운영 환경
- 상태: 설계 확정, 구현 계획 대기

## 1. 배경과 목적

운영 배포는 [docker-compose.prod.yml](../../../docker-compose.prod.yml)로 끝났지만 관측 수단이 없다.
지금 서버 상태를 아는 방법은 `docker compose ps` 와 `docker logs` 뿐이고, 둘 다 사람이
직접 들어가서 봐야 알 수 있다. 즉 **문제가 생겨도 사용자가 먼저 안다.**

이번 작업으로 얻으려는 것은 두 가지다.

1. **장애 감지와 알림** — 서비스가 죽거나 5xx가 튀면 사람이 접속하지 않아도 디스코드로 알려준다.
2. **성능 추적 대시보드** — 지연·처리량·JVM 힙을 시계열로 본다.

로그 수집(Loki)은 이번 범위에서 뺀다. 메모리 예산이 그만큼 없다(4장 참고).

로컬에는 이미 [docker-compose.monitoring.yml](../../../docker-compose.monitoring.yml)로 같은 스택이
있지만 **부하 테스트 전용**이다. `host.docker.internal` 로 스크레이프하고 MySQL 데이터소스가
`localhost:3307` 을 본다. 운영에 그대로 쓸 수 없어 별도 구성이 필요하다.

## 2. 범위

**포함**

- `docker-compose.prod.yml` 에 Prometheus / Grafana / Alertmanager / node-exporter 추가
- 운영 전용 `monitoring/prometheus/prometheus.prod.yml` 및 알림 규칙 파일
- 게이트웨이 `management.server.port` 분리 (보안, 3.2절)
- `application-aws.yml` 6종에 `/actuator/prometheus` 노출 추가
- 기존 컨테이너 9종에 `memswap_limit` 명시
- 외부 업타임 감시 설정
- 부하 테스트 절차와 초기화 절차 (7장)

**제외**

- 로그 수집(Loki/Promtail) — 메모리 예산 부족
- 분산 추적(Tempo/Jaeger) — 현 단계에서 과하다
- cAdvisor — 컨테이너별 자원은 node-exporter + JVM 지표로 갈음
- Grafana 외부 공개 — SSH 터널로만 접근하므로 nginx·인증서 작업 없음

## 3. 아키텍처

### 3.1 구성 요소

전부 기존 `comatching` 브리지 네트워크 안에 들어간다. **호스트에 여는 포트는
Grafana의 `127.0.0.1:3001` 하나뿐이다.**

| 서비스 | 이미지 | 호스트 포트 | mem_limit |
|---|---|---|---|
| prometheus | `prom/prometheus:v2.54.1` | 없음 | 512m |
| grafana | `grafana/grafana:11.2.0` | `127.0.0.1:3001:3000` | 256m |
| alertmanager | `prom/alertmanager:v0.27.0` | 없음 | 128m |
| node-exporter | `prom/node-exporter:v1.8.2` | 없음 | 64m |

이미지 태그는 로컬 모니터링 스택과 맞췄다. 버전이 갈리면 대시보드 JSON 호환 문제를
운영에서 처음 만나게 된다.

접근은 SSH 터널이다.

```
ssh -L 3001:localhost:3001 <user>@43.200.211.135
# 브라우저에서 http://localhost:3001
```

Grafana의 익명 접근은 **끈다**(`GF_AUTH_ANONYMOUS_ENABLED: "false"`). 로컬 스택은 켜 두었지만
운영은 터널 너머라도 계정을 요구하는 편이 낫다. 관리자 비밀번호는 `.env.prod` 로 넘긴다.

node-exporter는 호스트 메트릭을 읽어야 하므로 `/proc`, `/sys`, `/` 를 읽기 전용으로 마운트하고
`pid: host` 를 쓴다. 이 컨테이너만 예외적으로 호스트 네임스페이스를 본다.

### 3.2 게이트웨이 관리 포트 분리

**이 설계에서 가장 중요한 보안 결정이다.**

게이트웨이 라우트 목록([gateway-service/src/main/resources/application-aws.yml](../../../gateway-service/src/main/resources/application-aws.yml))에
`/actuator/**` 가 없다. Spring Cloud Gateway는 라우트에 걸리지 않은 경로를 자신이 처리하므로,
`/actuator/prometheus` 를 그냥 열면 게이트웨이 자신의 actuator가 응답한다. 그런데 게이트웨이
8080은 nginx가 TLS를 종단해 넘겨주는 대상이다. 결과적으로

```
https://srv.comatching.site/actuator/prometheus
```

가 **인증 없이 공개된다.** Prometheus 노출 지표에는 엔드포인트별 URI 패턴, 호출량, JVM 내부
상태가 다 들어 있어 내부 구조가 그대로 드러난다.

뒤쪽 5개 서비스(user 9000, matching 9001, chat 9003, notification 9005, item 9006)는 다르다.
게이트웨이 라우트에 actuator 경로가 없고 컴포즈에 `ports` 매핑도 없어서 외부에서 도달할 방법이
아예 없다. **위험한 것은 게이트웨이 하나뿐이다.**

해결은 게이트웨이만 관리 엔드포인트를 별도 포트로 옮기고 그 포트를 호스트에 매핑하지 않는 것이다.

```yaml
# gateway-service/src/main/resources/application-aws.yml
management:
  server:
    port: 8081          # 컴포즈에서 ports 로 열지 않는다
  endpoints:
    web:
      exposure:
        include: health,prometheus
  endpoint:
    health:
      show-details: never
```

이러면 `gateway-service:8081` 은 컴포즈 네트워크 안에서만 닿고, 8080으로 들어온 외부 요청은
`/actuator/*` 를 찾지 못해 라우트 매칭 실패로 떨어진다.

> **함께 고쳐야 할 것**: `docker-compose.prod.yml` 의 게이트웨이 healthcheck가
> `http://localhost:8080/actuator/health` 를 찌른다. 8081로 바꾸지 않으면 기동 직후
> healthcheck가 영구 실패하고, `depends_on: service_healthy` 가 걸린 것이 없더라도
> 컨테이너가 계속 unhealthy로 남는다.

나머지 5개 서비스는 포트를 분리하지 않는다. 외부 도달 경로가 없으므로 얻는 게 없고,
바꿀수록 healthcheck URL을 5군데 더 건드려야 한다. `exposure.include` 에 `prometheus` 만 더한다.

```yaml
# user/matching/chat/item/notification 의 application-aws.yml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
```

### 3.3 스크레이프 대상

`monitoring/prometheus/prometheus.prod.yml` 을 새로 만든다. 로컬용
[prometheus.yml](../../../monitoring/prometheus/prometheus.yml)과 두 가지가 다르다.

- `host.docker.internal` 이 아니라 **컨테이너 이름**으로 간다(같은 네트워크 안이므로).
- `scrape_interval` 을 5초가 아니라 **15초**로 둔다. 5초는 짧은 부하 테스트에서 데이터 포인트를
  확보하려던 값이라 상시 운영에는 과하다. 카디널리티와 TSDB 쓰기량이 3배가 된다.

```yaml
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
      # 게이트웨이만 관리 포트가 8081 이다 (3.2절 참고)
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

보존 기간은 15일, 크기 상한도 함께 건다. 디스크가 차서 죽는 것보다 오래된 데이터를 버리는 게 낫다.

```
--storage.tsdb.retention.time=15d
--storage.tsdb.retention.size=2GB
```

Grafana 데이터소스는 로컬의 [prometheus.yml](../../../monitoring/grafana/provisioning/datasources/prometheus.yml)을
재사용하되 `timeInterval` 을 `15s` 로 맞춘다. InfluxDB·MySQL 데이터소스는 운영에 올리지 않는다
(각각 JMeter 전용, 로컬 3307 전용).

## 4. 메모리 설계

### 4.1 예산

t3.large는 8 GiB(커널 제외 실사용 약 7.7 GiB)다. 현재 `mem_limit` 합이 6,080m이고
모니터링 960m을 더하면 **7,040m** 이 된다. 호스트 OS·docker 데몬·nginx 몫으로 약 860m이 남는다.

동작은 하지만 여유가 크지 않다. `mem_limit` 은 상한이지 예약이 아니므로 실사용은 더 낮을
가능성이 높지만, **추정으로 넘어가지 않고 7.2절에서 실측한다.**

여유가 부족하면 순서대로 손댄다.

1. Prometheus 보존 15일 → 7일
2. Kafka `-Xmx1g` / `mem_limit 1280m` → `-Xmx512m` / `768m`
   (단일 브로커·저트래픽 기준으로 현재 값이 후하다. 여기서만 512m이 확보된다)
3. 그래도 부족하면 t3.xlarge 승급

### 4.2 스왑

호스트에 **2 GB** 스왑 파일을 만들고 `vm.swappiness=1` 로 둔다.

4 GB가 아니라 2 GB인 이유는, 4.3절의 핀을 걸고 나면 스왑을 쓰는 주체가 호스트 프로세스
(sshd, systemd, docker 데몬)뿐이기 때문이다. 그쪽이 2 GB를 넘길 일이 없다. 크게 잡을수록
루트 EBS 볼륨의 용량과 IOPS만 더 떼어준다.

스왑을 아예 안 두지는 않는다. 메모리 압박이 걸렸을 때 **커널 OOM killer가 아무 프로세스나
고르기 전에 차가운 페이지를 밀어내 sshd를 살려두는 것** — 즉 들어가서 고칠 수 있는 상태를
유지하는 것이 목적이다.

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile \
  && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=1' | sudo tee /etc/sysctl.d/99-swap.conf && sudo sysctl --system
```

**선결 확인**: `docker info` 에 `WARNING: No swap limit support` 가 뜨면 안 된다.
이 경고가 있으면 4.3절의 `memswap_limit` 이 조용히 무시되어 핀이 걸리지 않는다.
`stat -fc %T /sys/fs/cgroup` 이 `cgroup2fs` 면 대개 정상이다. `tmpfs`(cgroup v1)면
커널 부팅 파라미터에 `swapaccount=1` 을 넣고 재부팅해야 한다.

### 4.3 memswap_limit — 스왑보다 중요한 결정

Docker는 `--memory` 만 주고 `--memory-swap` 을 생략하면 **memory+swap 합계를 `--memory` 의
2배**로 잡는다. 현재 `docker-compose.prod.yml` 은 전부 `mem_limit` 만 있다. 즉 호스트에
스왑을 켜는 순간 9개 컨테이너의 실효 상한이 조용히 두 배가 된다. 한도를 늘린 게 아니라
**한도를 무의미하게 만드는 것**에 가깝다.

특히 JVM과 스왑은 상성이 나쁘다. full GC가 힙 전체를 훑는데 그 페이지가 EBS 스왑에 있으면
GC 한 번이 수십 초로 늘어난다. healthcheck `timeout: 5s` 가 실패하고, 재시작이 다시 메모리를
흔드는 악순환이 된다. EBS는 네트워크 스토리지라 대역폭을 9개 컨테이너가 공유하므로
**한 컨테이너의 문제가 호스트 전체 브라운아웃**이 된다.

Redis는 더 나쁘다. `appendonly yes` 라 AOF rewrite 때 fork하며 copy-on-write가 걸리는데,
그 순간 페이지가 스왑에 있으면 지연이 폭발한다. 그리고 이 코드베이스는 이미
`maxmemory-policy noeviction` 을 "조용히 evict 되느니 시끄럽게 실패하는 게 낫다"는 이유로
골랐다. 스왑은 정확히 그 반대 방향이다.

그래서 **JVM 6종과 Redis는 스왑을 아예 쓰지 못하게 못박는다.**

| 컨테이너 | mem_limit | memswap_limit | 근거 |
|---|---|---|---|
| gateway-service | 512m | **512m** | JVM — 스왑 금지 |
| user-service | 768m | **768m** | JVM — 스왑 금지 |
| matching-service | 640m | **640m** | JVM — 스왑 금지 |
| chat-service | 576m | **576m** | JVM — 스왑 금지 |
| item-service | 640m | **640m** | JVM — 스왑 금지 |
| notification | 576m | **576m** | JVM — 스왑 금지 |
| redis | 320m | **320m** | fork/COW — 스왑 금지 |
| kafka | 1280m | 1536m | 여유 256m |
| mongodb | 768m | 896m | 여유 128m |
| prometheus | 512m | 512m | 스왑 금지 |
| grafana | 256m | 256m | 스왑 금지 |
| alertmanager | 128m | 128m | 스왑 금지 |
| node-exporter | 64m | 64m | 스왑 금지 |

`memswap_limit == mem_limit` 이면 해당 컨테이너의 스왑 사용량은 0이다. 지금과 똑같이
자기 cgroup 한도에서 OOMKill로 시끄럽게 죽는다. [Dockerfile:52](../../../Dockerfile)의
`-XX:+ExitOnOutOfMemoryError` 가 의도한 "좀비로 사느니 죽고 재시작한다"는 성질이 보존된다.

## 5. 알림

### 5.1 채널 분리

Alertmanager는 **`DLT_ALERT_WEBHOOK_URL` 과 다른 디스코드 웹훅**을 쓴다.
`.env.prod` 에 `INFRA_ALERT_WEBHOOK_URL` 을 새로 둔다.

두 알림은 성격이 반대다. DLT 알림은 "메시지 한 건이 처리에 실패했다"로 급하지 않고 잦다.
인프라 알림은 "서버가 아프다"로 지금 봐야 하고 드물다. 한 채널에 섞으면 잦은 쪽이 드문 쪽을
묻어버리고, 결국 둘 다 안 읽게 된다. 나누는 비용은 채널 하나와 웹훅 URL 하나(2분)이고,
안 나누는 비용은 알림을 무시하는 습관이다.

판단 기준은 **"이게 울리면 하던 일을 멈춰야 하나"** 다. DLT는 아니오, 인프라는 예다.

Alertmanager는 v0.25.0부터 `discord_configs` 를 네이티브로 지원한다. 구현 시 실제 버전에서
동작을 확인하고, 안 되면 `webhook_configs` + 얇은 변환 레이어로 대체한다.

### 5.2 규칙

`monitoring/prometheus/rules/alerts.yml` 로 둔다.

| 이름 | 조건 | for | 심각도 |
|---|---|---|---|
| ServiceDown | `up{job="comatching-services"} == 0` | 2m | critical |
| HighErrorRate | 5xx 비율 > 5% | 5m | critical |
| JvmHeapHigh | 힙 사용률 > 90% | 10m | warning |
| KafkaConsumerLag | `kafka_consumer_fetch_manager_records_lag_max > 1000` | 10m | warning |
| DiskSpaceLow | 루트 사용률 > 85% | 5m | warning |
| SwapInUse | 스왑 사용량 > 256MB | 10m | warning |

`SwapInUse` 를 넣는 이유가 있다. 4.3절의 핀 때문에 컨테이너는 스왑을 못 쓰므로,
스왑이 실제로 쓰이고 있다는 것은 **호스트가 메모리 압박을 받고 있다**는 뜻이다.
7,040m / 7.7 GiB 예산의 조기 경보 역할을 한다.

주요 PromQL:

```promql
# HighErrorRate
sum by (service) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  / sum by (service) (rate(http_server_requests_seconds_count[5m])) > 0.05

# JvmHeapHigh
sum by (service) (jvm_memory_used_bytes{area="heap"})
  / sum by (service) (jvm_memory_max_bytes{area="heap"}) > 0.9

# DiskSpaceLow
1 - node_filesystem_avail_bytes{mountpoint="/"}
  / node_filesystem_size_bytes{mountpoint="/"} > 0.85

# SwapInUse
node_memory_SwapTotal_bytes - node_memory_SwapFree_bytes > 256 * 1024 * 1024
```

`kafka_consumer_fetch_manager_records_lag_max` 는 Spring Kafka의 Micrometer 바인딩이 있어야
나온다. 구현 시 `/actuator/prometheus` 응답에서 실제 노출 여부를 확인하고, 없으면 해당 규칙을
빼거나 바인딩을 켠다.

## 6. 외부 업타임 감시

셀프호스팅 모니터링에는 근본 약점이 하나 있다. **EC2가 통째로 죽으면 알림도 같이 죽는다.**
"서버가 내려갔다"는 가장 중요한 알림을 그 서버가 보내야 하는 구조다.

이것만 외부 무료 서비스(UptimeRobot 등)로 메운다.

- 대상: `https://srv.comatching.site/actuator/health`
- 주기: 5분
- 알림: 인프라 디스코드 채널 (5.1절과 동일)

EC2 쪽 작업은 없고 설정은 웹에서 5분이면 끝난다. 이 한 겹으로 셀프호스팅의 유일한 사각지대가 덮인다.

## 7. 부하 테스트 절차

### 7.1 선결 과제

현재 도구를 운영에 그대로 쓸 수 없다. 세 가지를 고쳐야 한다.

**(1) JMeter 러너가 HTTPS를 못 쏜다.**
[run.ps1](../../../tools/perf/jmeter/run.ps1)이 `http://${TargetHost}:$Port` 로 URL을 만든다.
EC2의 게이트웨이 8080은 `127.0.0.1` 바인딩이라 외부에서 닿지 않으므로 nginx 443으로 가야 한다.
스킴 파라미터를 추가한다. JMX의 HTTP Sampler도 프로토콜이 변수로 빠져 있는지 확인이 필요하다.

**(2) 시드 로더가 RDS에서 동작하지 않는다.**
[load_seed.sh](../../../tools/perf/seed/load_seed.sh)는 두 가지를 전제한다.

- `docker exec comatching-mysql` — RDS는 컨테이너가 아니다
- 서버사이드 `LOAD DATA INFILE` — RDS는 DB 서버 파일시스템에 파일을 놓을 수 없고
  `secure_file_priv` 가 막혀 있다

RDS용으로는 **EC2 호스트에서 `mysql` 클라이언트로 `LOAD DATA LOCAL INFILE`** 을 쓰도록 고친다.
이때 RDS 파라미터 그룹에 `local_infile=1` 이 필요하고 클라이언트도 `--local-infile=1` 로 붙어야 한다.
`docker cp` / `chown` / `secure_file_priv` 탐지 단계는 통째로 빠진다.

**(3) 토큰을 운영 시크릿으로 다시 발급해야 한다.**
[generate_tokens.py](../../../tools/perf/tokens/generate_tokens.py)는 기본으로 로컬 `.env` 의
`JWT_SECRET` 을 읽는데, `.env.prod.example` 이 운영 시크릿은 로컬과 달라야 한다고 못박고 있다.
기존 `tokens.csv` 는 서명이 안 맞아 전부 401이다. `--env` 로 `.env.prod` 를 가리켜 재발급한다.

> **참고**: 시드는 `matching_candidate` 를 SQL로 직접 넣으므로 **Kafka를 거치지 않는다.**
> 따라서 `profile-updates` 토픽에 더미 이벤트가 쌓이지 않고, 늦게 도착한 이벤트가 후보를
> 되살리는 문제도 이 경로에서는 발생하지 않는다.

### 7.2 절차

런칭 전이라 실사용자가 없다. 운영 환경에 더미를 직접 넣고 매칭 경로까지 포함해 측정한다.
별도 스냅샷 환경은 필요 없다.

1. **베이스라인 측정** — 모니터링 올리기 전 평상시 실사용량
   ```bash
   docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}"
   ```
2. **스왑 + memswap_limit 적용** (4장)
3. **모니터링 스택 기동** (3장) — 대시보드로 볼 수 있어야 부하가 의미를 갖는다
4. **더미 시드** — `START_ID=1000001` 로 10만 건. `generate_seed.py` → RDS용으로 고친 로더
5. **토큰 재발급** — `python3 generate_tokens.py --env ../../../.env.prod`
6. **부하** — 사용자가 없는 시간대에 작은 램프부터.
   게이트웨이에 `RequestRateLimiter` 가 없어 부하가 걸러지지 않는다는 점에 유의
7. **측정** — 부하 중 `docker stats` 를 CSV로 남기고 Grafana로 함께 본다
   ```bash
   docker stats --format "{{.Name}},{{.MemUsage}},{{.MemPerc}},{{.CPUPerc}}" >> ~/stats-$(date +%F-%H%M).csv
   ```
8. **판단** — 4.1절 예산과 대조. 부족하면 4.1의 완화 순서대로

### 7.3 초기화

측정이 끝나면 런칭 전에 전부 민다. **저장소 종류가 둘이라 방식도 둘이다.**

`TRUNCATE` 는 쓰지 않는다. `profile.member_id` 가 `members` 를 FK로 참조하고
`profile_hobby`/`profile_tag` 가 다시 `profile` 을 참조해서, MySQL InnoDB가 에러 1701로 거부한다.
`SET FOREIGN_KEY_CHECKS=0` 으로 우회할 수는 있지만 아래가 더 단순하다.

```bash
# 1) 컨테이너 볼륨 — kafka/mongo/redis 상태 제거
cd ~/comatching
docker compose -f docker-compose.prod.yml --env-file .env.prod down -v

# 2) RDS — Hibernate가 세 스키마를 drop 후 재생성
grep -q '^JPA_DDL_AUTO=' .env.prod \
  && sed -i 's/^JPA_DDL_AUTO=.*/JPA_DDL_AUTO=create/' .env.prod \
  || echo 'JPA_DDL_AUTO=create' >> .env.prod

docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

`down -v` 가 `kafka-data` / `mongo-data` / `redis-data` 를 날린다. RDS는 외부라 볼륨 삭제로
지워지지 않으므로 `ddl-auto: create` 로 재생성한다. 스키마가
`comatching_user` / `comatching_matching` / `comatching_item` 으로 분리돼 있어 서비스별로
자기 테이블만 다시 만든다. FK 순서를 사람이 신경 쓸 일이 없다.

> **대안**: `load_seed.sh` 는 적재 전에 `member_id >= START_ID` 인 행을 지우는 로직을 이미
> 갖고 있다. 즉 시드 행만 ID 범위로 골라 지우는 것도 가능하다. 런칭 후에 다시 부하를 걸
> 일이 생기면 이쪽을 써야 한다 — 그때는 `ddl-auto: create` 를 절대 쓸 수 없다.

**그리고 반드시 되돌린다.**

```bash
sed -i 's/^JPA_DDL_AUTO=.*/JPA_DDL_AUTO=validate/' .env.prod && grep JPA_DDL_AUTO .env.prod
```

`ddl-auto` 는 부팅 시점에만 적용되므로 지금 떠 있는 컨테이너에는 영향이 없고 다음 재기동부터
`validate` 로 돈다. **이걸 빠뜨리면 실서비스 데이터가 언젠가 조용히 전부 사라진다.**
CI가 main 푸시마다 `up -d` 를 하고, `restart: unless-stopped` 로 컨테이너가 스스로 재시작하기도
한다. `.env.prod.example` 이 경고하는 바로 그 함정이며, 런칭 직전이라 특히 위험하다.

## 8. 리스크와 미결 사항

| 항목 | 내용 | 대응 |
|---|---|---|
| 메모리 예산 | 7,040m / 7.7 GiB로 여유가 860m뿐 | 7.2절 실측 후 4.1절 완화 순서대로 |
| cgroup 스왑 계정 | v1이면 `memswap_limit` 이 무시됨 | 4.2절 선결 확인. 미지원이면 스왑 도입 보류 |
| Alertmanager 디스코드 | `discord_configs` 동작을 실제 버전에서 미검증 | 구현 시 확인, 실패하면 `webhook_configs` 대체 |
| Kafka lag 지표 | Micrometer 바인딩 노출 여부 미확인 | `/actuator/prometheus` 응답으로 확인 후 규칙 확정 |
| nginx 설정 | 저장소 밖이라 `/actuator` 처리 방식 미확인 | 3.2절이 관리 포트 분리로 nginx와 무관하게 해결 |
| 부하 커버리지 | 공개 엔드포인트만으로는 matching/chat/item 유휴 | 더미 시드로 매칭까지 포함(7.2절) |

## 9. 구현 순서

1. 게이트웨이 관리 포트 분리 + `application-aws.yml` 6종 `prometheus` 노출 (3.2절)
2. `docker-compose.prod.yml` 에 `memswap_limit` 명시 (4.3절)
3. `monitoring/prometheus/prometheus.prod.yml` + `rules/alerts.yml` 작성 (3.3, 5.2절)
4. Alertmanager 설정 + `.env.prod.example` 에 `INFRA_ALERT_WEBHOOK_URL` 추가 (5.1절)
5. `docker-compose.prod.yml` 에 모니터링 4종 추가 (3.1절)
6. JMeter 러너 HTTPS 지원 + 시드 로더 RDS 대응 (7.1절)
7. 외부 업타임 감시 등록 (6장)

1~5는 코드 변경이고 6은 도구 변경이라 서로 독립적이다.
스왑(4.2절)은 EC2에서 직접 수행하며 저장소 변경이 아니다.
