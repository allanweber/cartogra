---
paths:
  - "infra/**"
  - ".github/**"
  - "**/Dockerfile"
  - "**/*.gradle.kts"
---

# Infrastructure Rules

## Dockerfile

- Multi-stage REQUIRED: `builder` stage (`eclipse-temurin:25-jdk-jammy`) → `runtime` stage (`eclipse-temurin:25-jre-jammy`)
- Layer order for cache efficiency: base image → OS deps → create user → copy build descriptors → fetch deps → copy source → build → runtime COPY → USER / ENTRYPOINT
- Non-root user REQUIRED: `RUN useradd -m -u 1000 appuser` → `USER appuser`
- JVM container sizing: `-XX:MaxRAMPercentage=75` — NEVER hard-set `-Xmx`/`-Xms` in container environments
- ENTRYPOINT: `["sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]` (enables signal passthrough for graceful shutdown)
- COPY only — NEVER `ADD`
- `.dockerignore` must exclude: `.git`, `build/`, `target/`, `*.md`, `.env`, `node_modules`
- `HEALTHCHECK CMD curl -f http://localhost:8080/actuator/health/live || exit 1`
- NEVER use image tag `latest`

## Kubernetes

- Resource requests AND limits REQUIRED on every container — NEVER omit either field
- Sizing: limits ≈ 2× requests for CPU; memory limit = memory request (Guaranteed QoS, prevents OOM eviction)
- Three probes REQUIRED on every Deployment:
  - `livenessProbe` → `/actuator/health/live` (kill + restart if fails)
  - `readinessProbe` → `/actuator/health/ready` (remove from Service endpoints if fails)
  - `startupProbe` → `/actuator/health` (kill if startup exceeds timeout)
- Pod `securityContext`: `runAsNonRoot: true`, `runAsUser: 1000`, `fsGroup: 1000`
- Container `securityContext`: `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`
- Mount `/tmp` as `emptyDir` when `readOnlyRootFilesystem: true`
- Secrets: mount as volumes — NEVER as env vars; NEVER commit Secret YAMLs to Git (use sealed-secrets or external manager)
- HA values (multi-node): `PodDisruptionBudget` `minAvailable: 2`; HPA `minReplicas ≥ PDB.minAvailable`, CPU + memory targets, `scaleDown.stabilizationWindowSeconds: 300`
- Single-node production (`values-production.yaml`, ADR-0032): PDB and HPA off, one replica — do not add them there
- Rolling update: `maxUnavailable: 0`, `maxSurge: 1` (zero-downtime)
- Service type: `ClusterIP` for internal services; external traffic via Ingress only — NEVER `LoadBalancer` for internal
- Required labels: `app`, `version`, `environment`, `team`
- Namespaces: `cartogra` (product) · `acme-system` (Acme release) · `acme-fintech` (fake services) · `ci-deploy` (CI identity)
- NEVER `privileged: true`; NEVER `runAsUser: 0`

## CI / Build

- Gradle multi-module: `./gradlew build` from repo root — all services build together
- Multi-stage Dockerfile for EVERY deployable JVM service and the frontend
- Trivy scan on every PR — block merges with CRITICAL or HIGH CVEs
- Conventional commits: `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`
- Branch strategy: `main` (protected, PRs only) · `feat/<name>` · `fix/<name>`
- NEVER bypass hooks with `--no-verify`
