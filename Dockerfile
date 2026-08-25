# syntax=docker/dockerfile:1
# ============================================================
# 서비스 6개가 한 파일을 공유한다. 빌드할 대상은 인자로 받는다.
#   docker build --build-arg SERVICE=user-service -t comatching/user-service .
# docker-compose.prod.yml 이 서비스마다 이 인자를 넘긴다.
# ============================================================

# ---------- 빌드 ----------
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY . .

# gradlew 가 Windows 에서 커밋되어 줄바꿈이 CRLF 다. 리눅스 셸은 끝의 캐리지
# 리턴을 인터프리터 경로의 일부로 읽어서 "bad interpreter: /bin/sh^M" 으로 죽는다.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# 여기서 6개 서비스의 jar 를 한 번에 만든다. 이 스테이지에 ARG SERVICE 를 두지
# 않는 것이 핵심이다 - 명령이 서비스마다 동일해야 도커가 이 층을 재사용하고,
# 그래야 이미지 6개를 빌드할 때 Gradle 이 6번이 아니라 1번만 돈다.
# vCPU 2개짜리 인스턴스에서 빌드하면 이 차이가 10분 단위로 벌어진다.
# common-module 은 bootJar 가 꺼져 있어 대상에서 알아서 빠진다.
#
# BuildKit 캐시 마운트: 의존성 캐시를 이미지 층에 남기지 않으면서도
# 재빌드 때 다운로드를 건너뛴다.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar

# ---------- 실행 ----------
FROM eclipse-temurin:17-jre-jammy AS runtime
ARG SERVICE

# curl: 컴포즈 헬스체크가 /actuator/health 를 찌른다.
# tzdata: 로그·스케줄러·MySQL serverTimezone 이 모두 KST 기준이다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && ln -snf /usr/share/zoneinfo/Asia/Seoul /etc/localtime \
 && echo "Asia/Seoul" > /etc/timezone \
 && useradd --system --uid 10001 --shell /usr/sbin/nologin app

WORKDIR /app
# bootJar 만 실행했으므로 build/libs 에는 실행 가능 jar 하나만 있다
# (jar 태스크가 만드는 -plain.jar 는 생성되지 않는다).
COPY --from=build /workspace/${SERVICE}/build/libs/*.jar /app/app.jar
RUN chown app:app /app/app.jar
USER app

# -Xmx 를 고정하지 않고 컨테이너 메모리 한도에 비례시킨다. 컴포즈에서
# mem_limit 만 조정하면 힙이 따라 움직인다.
# ExitOnOutOfMemoryError: OOM 뒤 좀비로 사는 것보다 죽고 재시작되는 편이 낫다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Seoul -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
