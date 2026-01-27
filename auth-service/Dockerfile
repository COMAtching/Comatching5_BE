# =================================================================================
# 1. Build Stage
# =================================================================================
FROM amazoncorretto:17-alpine AS builder

WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradle ./gradle
COPY build.gradle .
COPY settings.gradle .

# Copy module build.gradle files for dependency caching
COPY auth-service/build.gradle ./auth-service/
COPY common-module/build.gradle ./common-module/

# Download dependencies
RUN chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon || true

# Copy source code
COPY auth-service/src ./auth-service/src
COPY common-module/src ./common-module/src

# Build
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :auth-service:bootJar -x test --no-daemon

# =================================================================================
# 2. Run Stage
# =================================================================================
FROM amazoncorretto:17-alpine

WORKDIR /app

COPY --from=builder /app/auth-service/build/libs/*.jar app.jar

ENV TZ=Asia/Seoul
ENV SPRING_PROFILES_ACTIVE=docker

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]

EXPOSE 9000
