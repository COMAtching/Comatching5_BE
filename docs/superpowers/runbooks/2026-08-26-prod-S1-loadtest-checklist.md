# 운영 부하 1차 (S1 읽기 전용) — D-day 체크리스트

계획: 스모크 → 1만 리허설 → 10만 본판. 전부 **읽기 전용(S1)** — 매칭·구매는 안 친다.
부하기: 로컬 PC (JMeter 5.6.3, `~/apps/apache-jmeter-5.6.3`). 대상: `srv.comatching.site`.

**이번 회차는 부하기와 대상이 분리된 첫 측정이다.** 이전 회차(단일 맥)와 절대 수치를
직접 비교하지 말 것. 호스트 CPU 70% 폐기 기준은 이제 "부하기 PC CPU"에만 적용된다.

## 0. 사전 완료된 것 (2026-08-26)

- [x] 공개 스모크 6/6 통과 (`tools/perf/smoke.sh`)
- [x] JMeter 5.6.3 설치, `run.sh` Windows 호환 패치
- [x] 로컬 InfluxDB(8086)·Grafana(**3002** — 3001 은 다른 프로젝트가 점유) 기동
- [x] 1만 시드 TSV 생성 (`tools/perf/seed/out/`)
- [ ] RDS 파라미터 그룹 `local_infile=1` (콘솔에서 확인/적용)

## 1. EC2 준비 (SSH: ubuntu@43.200.211.135)

```bash
# 모니터링 6타깃 up=1 확인
docker exec comatching-prometheus wget -qO- 'http://localhost:9090/api/v1/query?query=up'

# 디스코드에 "부하 테스트 공지" (또는 Alertmanager silence)
```

로컬에서 서버 Grafana 터널: `ssh -L 3001:localhost:3001 ubuntu@43.200.211.135`
→ 브라우저 `http://localhost:3001` (서버 지표) + `http://localhost:3002` (JMeter 클라 지표)

## 2. 시드·토큰 (리허설은 1만으로)

```bash
# [로컬] TSV 업로드
scp -r tools/perf/seed/out ubuntu@43.200.211.135:~/comatching/tools/perf/seed/

# [EC2] RDS 적재 — 시드 ID 는 1000001 부터라 나중에 범위 삭제 가능
set -a; . ~/comatching/.env.prod; set +a
cd ~/comatching/tools/perf/seed && ./load_seed_rds.sh
# 검사 3종(행 수 / age_is_null=0 / gender 5:5) 통과 확인

# [EC2] 토큰 발급 (운영 JWT_SECRET 필요 — 로컬 불가)
cd ../tokens && python3 generate_tokens.py --env ~/comatching/.env.prod

# [로컬] 토큰 회수
scp ubuntu@43.200.211.135:~/comatching/tools/perf/tokens/tokens.csv tools/perf/tokens/
```

토큰 검증 + 인증 스모크 (로컬):

```bash
TOKEN=$(head -1 tools/perf/tokens/tokens.csv | cut -d, -f2)
ACCESS_TOKEN=$TOKEN ./tools/perf/smoke.sh    # 11개 전부 ✅ 여야 진행
```

## 3. 실행 (사용자 없는 시간대 — 게이트웨이에 rate limiter 없음)

```bash
# [EC2] 실측 기록 시작 (부하 끝나면 Ctrl-C)
docker stats --format "{{.Name}},{{.MemUsage}},{{.MemPerc}},{{.CPUPerc}}" >> ~/stats-$(date +%F-%H%M).csv

# [로컬 Git Bash] 부하 실행 — 워밍업 60초 + 50→100→200→400→800 RPS
export PATH="$HOME/apps/apache-jmeter-5.6.3/bin:$PATH"
cd tools/perf/jmeter
SCHEME=https HOST=srv.comatching.site PORT=443 ./run.sh
```

**중단 기준 (하나라도 걸리면 Ctrl-C):**
- EC2 메모리: 컨테이너 합계가 한도(여유 860m) 육박, 스왑 급증, OOM 킬
- 에러율이 계단 초반부터 10% 이상 (측정이 아니라 장애다)
- ServiceDown 알림 발화

## 4. 판정·기록

- knee 판정은 `summarize.py` 자동: p95 > 500ms / 에러율 > 1% / 2배 부하에 처리량 1.2배 미만
- 부하기 PC CPU 를 작업관리자로 관찰 — 70% 초과 구간은 폐기
- 클라 p95(Grafana 3002) vs 서버 p95(터널 3001) gap 확인
- `docs/perf-log.md` 에 회차 기록 (환경: "부하기 분리, 대상 운영 EC2+RDS" 명시)

## 5. 리허설 → 본판

리허설(1만)이 절차상 문제 없으면:

```bash
# [로컬] 10만 재생성 후 2단계 반복 (load_seed_rds.sh 가 기존 시드를 지우고 새로 적재)
PYTHONIOENCODING=utf-8 python tools/perf/seed/generate_seed.py --count 100000
```

## 6. 정리

- 시드 삭제는 급하지 않다 — 런칭 전 초기화(런북 §9 `down -v` + `create`)에서 일괄 소멸.
  그 전에 지우려면 ID ≥ 1000001 범위 삭제.
- `~/stats-*.csv` 백업, 디스코드에 부하 종료 공지, Alertmanager silence 해제
- S1 은 읽기 전용이라 `matching_history`/`item.quantity` 오염 없음 — 리셋 불필요
