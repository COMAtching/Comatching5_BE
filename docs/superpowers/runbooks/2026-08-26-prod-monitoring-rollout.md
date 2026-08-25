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
