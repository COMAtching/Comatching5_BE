# =================================================================================
# 1. 빌드 스테이지 (Build Stage)
# =================================================================================
FROM --platform=$BUILDPLATFORM amazoncorretto:17-alpine-jdk AS builder

WORKDIR /app

# 루트 수준의 Gradle 설정 복사
COPY gradlew .
COPY gradle ./gradle
COPY build.gradle .
COPY settings.gradle .

# 각 모듈의 build.gradle만 먼저 복사 (의존성 캐싱 최적화)
COPY auth-service/build.gradle ./auth-service/
COPY common-module/build.gradle ./common-module/

# 실행 권한 부여 및 의존성 다운로드
RUN chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon || true

# 실제 소스 코드 복사 (이제 소스가 바뀔 때만 여기서부터 다시 빌드됨)
COPY auth-service/src ./auth-service/src
COPY common-module/src ./common-module/src

# Auth Service 빌드 (common-module은 알아서 같이 빌드됨)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :auth-service:bootJar -x test --no-daemon

# =================================================================================
# 2. 실행 스테이지 (Run Stage)
# =================================================================================
FROM amazoncorretto:17-alpine-jdk

WORKDIR /app

# 빌드 스테이지 결과물(Jar) 복사
# 멀티모듈이므로 build/libs 경로가 모듈 안에 생성됩니다.
COPY --from=builder /app/auth-service/build/libs/*.jar app.jar

# 타임존 설정
ENV TZ=Asia/Seoul

# 실행 명령어
# 프로파일은 docker-compose에서 환경변수로 덮어쓸 수 있습니다.
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]

EXPOSE 9000