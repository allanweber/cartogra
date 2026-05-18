# Plan: Phase 0 Gate — Close the Gate

## Context

All Phase 0 checklist tasks (0.1–0.48) are marked `[x]`. This plan identifies the gaps blocking Phase 0 gate clearance and the exact work needed to close each one before Phase 1 begins.

## Current State of Each Gate Item

| Gate Item | Status | Gap |
|-----------|--------|-----|
| DESIGN.md and PRODUCT.md exist with enough detail | ✅ Both exist and are complete | None |
| `shared:contracts` compiles with protobuf plugin; no proto in service modules | ✅ Module configured; protos only in `shared/contracts/src/` | Verify build is green |
| `./gradlew build` and CI green; Trivy policy documented | ⚠️ CI configured with CRITICAL,HIGH, exit-code:1 | Rename Trivy step to make policy explicit |
| `docker compose up` succeeds with all services healthy | ❌ gateway, registry, ingestion NOT in any compose file | Add app services to `docker-compose.yml` |
| Flyway clean migrate passes from blank DB in automation | ✅ `FlywayMigrationSmokeTest` exists via Testcontainers | None |
| At least one endpoint proves envelope + `X-Trace-Id` | ✅ `GET /ping` in gateway and registry | None |
| Frontend installs and runs; CI lint/typecheck/test pass | ✅ Full TanStack Start setup, all scripts present | None |
| Minimum BIP set: launch thread + data-model/Kafka thread + service-catalog blog + ADR article | ✅ 0028/0029 on main (`docs/bips/`); 0.45/0.46 on current branch | None |

---

## Implementation Plan

### Step 1 — Add app services to `infra/docker-compose/docker-compose.yml`

This is the primary blocking gap. Add gateway (8080), registry (8081), and ingestion (8085) to `docker-compose.yml` — the base infra file that already has postgres, valkey, and kafka. `otel-collector` lives only in `docker-compose.dev.yml`, so OTLP env vars are omitted here; they flow only when the dev overlay is composed on top. Spring Boot handles a missing OTLP endpoint gracefully (best-effort, no startup failure).

```
docker compose -f infra/docker-compose/docker-compose.yml up --build
```

**Structure for each service** (gateway shown, adapt port/name for registry/ingestion):

```yaml
gateway:
  build:
    context: ../..          # repo root, where Gradle wrapper lives
    dockerfile: infra/docker/gateway/Dockerfile
  image: cartogra/gateway:local
  container_name: cartogra-gateway
  ports:
    - "8080:8080"
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/cartogra
    SPRING_DATASOURCE_USERNAME: cartogra
    SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:-cartogra}
    SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:29092
    SPRING_DATA_REDIS_HOST: valkey         # gateway only
    SPRING_DATA_REDIS_PORT: 6379           # gateway only
    MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health,metrics,info
    SPRING_PROFILES_ACTIVE: docker
  depends_on:
    postgres:
      condition: service_healthy
    valkey:
      condition: service_healthy
    kafka:
      condition: service_healthy
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health/live"]
    interval: 15s
    timeout: 5s
    retries: 10
    start_period: 60s     # JVM startup + Flyway
  networks:
    - cartogra-net
```

Registry and ingestion use separate Flyway history tables (per the 2026-05-05 note in the checklist). Add `SPRING_FLYWAY_TABLE` for each:
- Registry: `SPRING_FLYWAY_TABLE: flyway_schema_history_registry`
- Ingestion: `SPRING_FLYWAY_TABLE: flyway_schema_history_ingestion`

Gateway does NOT need `SPRING_FLYWAY_TABLE` (no per-service isolation needed there).

Redis env vars (`SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`) apply to gateway only. Registry and ingestion omit them.

### Step 2 — Document Trivy policy in `.github/workflows/ci.yml`

Change the Trivy step `name` from `Trivy vulnerability scan` to:
```
Trivy vulnerability scan — fail on CRITICAL or HIGH (unfixed suppressed)
```
This is the only documentation gap; the behavior (exit-code, severity, ignore-unfixed) is already correct.

### Step 3 — GitHub workflow

Per `workflow.md` rules:

1. **Issue**: issue #4 "US0.4 — Project is legible to outsiders" is OPEN — reuse it. If it was closed by the previous PR, open `US0.4b — Phase 0 gate: app services in compose + missing BIP drafts`
2. **Branch**: `feat/phase-0-gate`
3. **Commit** (after explicit user approval):
   ```
   feat(infra): wire app services into compose; document Trivy policy

   - Add gateway (8080), registry (8081), ingestion (8085) to docker-compose.yml
     with build context, healthchecks, env vars, and depends_on
   - Rename Trivy CI step to document CRITICAL/HIGH failure policy explicitly

   Closes #4

   Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
   ```
4. **PR**: link issue, milestone Phase 0 — Foundation

---

## Files to Create / Modify

| Action | Path |
|--------|------|
| Edit | `infra/docker-compose/docker-compose.yml` (add gateway, registry, ingestion) |
| Edit | `.github/workflows/ci.yml` (rename Trivy step) |

No checklist changes needed. BIP 0.28 and 0.29 already exist on main (`docs/bips/`). All Phase 0 Gate items are verified by running the commands below.

---

## Verification

1. **Build**: `./gradlew build` from repo root — must be green
2. **Docker stack**:
   ```
   docker compose -f infra/docker-compose/docker-compose.yml up --build -d
   docker compose -f infra/docker-compose/docker-compose.yml ps
   ```
   All services must reach `healthy` status: gateway, registry, ingestion, postgres, valkey, kafka
3. **Envelope endpoint**: `curl -s localhost:8080/ping | jq .` — must show `data`, `traceId`; response headers must include `X-Trace-Id`
4. **Frontend**: `cd frontend && pnpm install --frozen-lockfile && pnpm run typecheck && pnpm test`
5. **BIP files**: `ls docs/bip/` — must include `0.28-*`, `0.29-*`, `0.45-*`, `0.46-*`
