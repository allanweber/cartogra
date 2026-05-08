FROM eclipse-temurin:25-jdk-jammy AS builder
WORKDIR /build

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY infra/docker/settings.docker.gradle.kts ./settings.gradle.kts
COPY shared/ shared/
COPY services/gateway/ services/gateway/

RUN --mount=type=cache,target=/root/.gradle chmod +x ./gradlew && ./gradlew -Ddocker.service=gateway :services:gateway:bootJar -x test --no-daemon

FROM eclipse-temurin:25-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -m -u 1000 appuser

WORKDIR /app
COPY --from=builder --chown=appuser:appuser /build/services/gateway/build/libs/gateway.jar app.jar

USER appuser
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -XX:MaxRAMPercentage=75 -jar app.jar"]
