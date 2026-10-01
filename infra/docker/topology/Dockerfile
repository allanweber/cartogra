FROM eclipse-temurin:25-jdk-jammy AS builder
WORKDIR /build

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY infra/docker/settings.docker.gradle.kts ./settings.gradle.kts
COPY shared/ shared/
COPY services/topology/ services/topology/

RUN --mount=type=cache,target=/root/.gradle,sharing=locked chmod +x ./gradlew && ./gradlew -Ddocker.service=topology :services:topology:bootJar -x test --no-daemon

FROM eclipse-temurin:25-jre-jammy AS runtime

# --only-upgrade libssl3 pulls the jammy-security fix (CVE-2026-84782) for whatever's
# already on the base image, without a general `apt-get upgrade -y` pulling in
# unrelated package churn.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && apt-get install -y --only-upgrade libssl3 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -m -u 1000 appuser

WORKDIR /app
COPY --from=builder --chown=appuser:appuser /build/services/topology/build/libs/topology.jar app.jar

USER appuser
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 CMD curl -f http://localhost:8082/api/v1/topology/actuator/health/live || exit 1
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -XX:MaxRAMPercentage=75 -jar app.jar"]
