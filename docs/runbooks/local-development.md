# Runbook: Local Development

## Prerequisites

| Tool | Minimum version | Install |
|------|----------------|---------|
| JDK | 25 | [Eclipse Temurin](https://adoptium.net/) |
| Gradle | 8.14 | Bundled via `./gradlew` wrapper |
| Docker Desktop | 4.x | [docker.com](https://www.docker.com/products/docker-desktop/) |
| Node.js | 22 LTS | [nodejs.org](https://nodejs.org/) |
| pnpm | 9.x | `npm install -g pnpm` |

---

## 1. Clone and bootstrap

```bash
git clone https://github.com/your-org/cartogra.git
cd cartogra
cp .env.example .env          # fill in secrets (see §2)
```

---

## 2. Environment variables (`.env`)

Copy `.env.example` to `.env`. The following variables are required to start all services locally:

| Variable | Description |
|----------|-------------|
| `POSTGRES_PASSWORD` | Local Postgres superuser password |
| `REDIS_PASSWORD` | Local Redis auth password |
| `JWT_SECRET` | 256-bit secret for gateway JWT signing |
| `RESEND_API_KEY` | Resend API key for OTP emails (`re_...`) |
| `GITHUB_PAT` | GitHub Personal Access Token with `repo` scope (for SCM ingestion) |
| `AZDO_PAT` | Azure DevOps Personal Access Token (for SCM ingestion) |
| `AZDO_ORG` | Azure DevOps organization name |
| `CLAUDE_API_KEY` | Anthropic API key (skip if not testing intelligence service) |

**Never commit `.env`** — it is in `.gitignore`.

---

## 3. Start infrastructure

```bash
docker compose -f infra/docker-compose/docker-compose.dev.yml up -d \
  postgres valkey kafka tempo loki prometheus otel-collector grafana
```

Wait for healthy status:

```bash
docker compose -f infra/docker-compose/docker-compose.dev.yml ps
```

Expected: all containers `(healthy)` or `Up`. Kafka may take 20–30 seconds on first start. Tempo and Loki must be healthy before `otel-collector` starts.

---

## 4. Run database migrations

Flyway runs automatically on service startup. To run migrations manually:

```bash
./gradlew :services:registry:flywayMigrate
./gradlew :services:topology:flywayMigrate
./gradlew :services:contract:flywayMigrate
./gradlew :services:intelligence:flywayMigrate
```

---

## 5. Start backend services

Open one terminal per service, or use the IntelliJ "Run Configurations":

```bash
# Terminal 1 — Gateway (port 8080)
./gradlew :services:gateway:bootRun

# Terminal 2 — Registry (port 8081)
./gradlew :services:registry:bootRun

# Terminal 3 — Topology (port 8082)
./gradlew :services:topology:bootRun

# Terminal 4 — Contract (port 8083)
./gradlew :services:contract:bootRun

# Terminal 5 — Intelligence (port 8084)
./gradlew :services:intelligence:bootRun

# Terminal 6 — Ingestion (port 8085)
./gradlew :services:ingestion:bootRun
```

Each service logs `Started <Name>Application` when ready.

---

## 6. Start the frontend

```bash
cd frontend
pnpm install
pnpm dev          # http://localhost:3000
```

The TanStack Start dev server proxies `/api/*` to `http://localhost:8080`.

---

## 7. Seed data

Load the Acme Fintech seed dataset for a realistic local environment:

```bash
./gradlew :seed:run
```

This creates:
- 1 tenant (`acme-fintech`)
- 6 teams
- 24 services with ownership, dependencies, and health data
- Sample API contracts and breaking-change history

---

## 8. Useful local URLs

| URL | Purpose |
|-----|---------|
| `http://localhost:3000` | Frontend |
| `http://localhost:3001` | Grafana (traces + logs + metrics) |
| `http://localhost:8080/actuator/health` | Gateway health |
| `http://localhost:8081/actuator/health` | Registry health |
| `http://localhost:5436` | PostgreSQL (`psql -U postgres -d cartogra`) |
| `http://localhost:6379` | Redis (`redis-cli`) |
| `http://localhost:9092` | Kafka bootstrap |
| `http://localhost:9090` | Prometheus (direct query) |
| `http://localhost:3200` | Tempo HTTP API |
| `http://localhost:8086` | Kafka UI |
| `http://localhost:8087` | Valkey UI |
| `http://localhost:8088` | pgAdmin |

---

## 9. Running tests

```bash
# All tests (unit + integration via Testcontainers)
./gradlew test

# Single service
./gradlew :services:registry:test

# Frontend
cd frontend && pnpm test
```

Testcontainers spins up ephemeral Postgres and Kafka containers. Docker Desktop must be running.

---

## 10. Common issues

### Port already in use

```bash
# Find and kill the process using the port (e.g., 8081)
lsof -ti:8081 | xargs kill -9   # macOS/Linux
netstat -ano | findstr :8081     # Windows (then taskkill /PID <PID> /F)
```

### Kafka consumer lag not clearing

```bash
docker compose -f infra/docker-compose/docker-compose.dev.yml restart kafka
# Wait 15 seconds, then restart the consumer service
./gradlew :services:topology:bootRun
```

### Migration checksum mismatch

Never edit a committed Flyway migration. If you need to fix a mistake, create a new migration (`V00N__fix_...`). If on a local-only branch, drop and recreate the database:

```bash
docker compose -f infra/docker-compose/docker-compose.dev.yml down -v postgres
docker compose -f infra/docker-compose/docker-compose.dev.yml up -d postgres
./gradlew :services:registry:flywayMigrate
```

### Flyway error: non-empty schema but no schema history table

Symptom:
- `Found non-empty schema(s) "public" but no schema history table`

Cause:
- Service points Flyway at shared `public` while using independent startup migrations.

Required setup per DB-backed service:
- JDBC URL uses `currentSchema=<service_schema>`
- Flyway sets `schemas=<service_schema>` and `default-schema=<service_schema>`
- Flyway history table is `flyway_schema_history` in that service schema

Example (`registry`):

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5436/cartogra?currentSchema=registry
  flyway:
    create-schemas: true
    schemas: registry
    default-schema: registry
    table: flyway_schema_history
```

### OTel traces not appearing in Grafana/Tempo

Check `OTEL_EXPORTER_OTLP_ENDPOINT` in your shell (default: `http://localhost:4317`). Verify the observability stack is running and healthy:

```bash
docker compose -f infra/docker-compose/docker-compose.dev.yml ps
# tempo, loki, prometheus, otel-collector, grafana should all be (healthy)
```

Query Tempo directly to confirm traces are arriving:

```bash
curl "http://localhost:3200/api/search?limit=5"
```

Query Loki directly to confirm logs are arriving:

```bash
curl -G "http://localhost:3100/loki/api/v1/query" \
  --data-urlencode 'query={service_name="gateway"}' \
  --data-urlencode 'limit=5'
```

Query Prometheus to confirm metrics are arriving:

```bash
curl "http://localhost:9090/api/v1/query?query=cartogra_jvm_memory_used_bytes"
```

Open `http://localhost:3001` → Explore → Tempo Search to browse traces. Click any span to navigate to correlated Loki logs and RED metrics panels.

## Testing observed edges (OTel spans)

Observed edges (`dependency_type = 'observed'`, [ADR-0029](../adr/0029-observed-edges-from-otel-spans.md)) are built from OpenTelemetry spans. There is no UI to enable it yet — the setup below is manual until roadmap 5.9 (see [Limitations](#limitations)).

### How it flows

```
instrumented service ──OTLP──▶ otel-collector ──▶ Kafka cartogra.observability.spans ──▶ Topology OtelSpanWorker
                               (stamps tenant)                                           │ resolves CLIENT spans to catalog services
                                                                                         ▼
                                              topology.dependencies (observed) ──▶ dependency_graph_view ──▶ /graph?type=OBSERVED
```

- The collector has two trace pipelines: `traces` (→ Tempo, unchanged) and `traces/spans` (→ Kafka). The second stamps the resource attribute `cartogra.tenant.id` and drops Topology's own spans (`service.name == "topology"`), otherwise consuming a record would create the next one.
- **CLIENT** spans with `http.request.method` / `http.method` (→ `http`) or `rpc.system=grpc` (→ `grpc`) become edges.
- **PRODUCER/CONSUMER** spans with `messaging.system=kafka` and `messaging.destination.name` (→ `kafka`) are joined on the topic name: each producer of a topic gets an edge to each consumer of it. No shared trace is needed. Each (topic, producer/consumer, service) is one row in `topology.pending_span_links`, kept for `topology.span-links.messaging-ttl` after the last span.
- **CLIENT** spans with `db.system` (→ `db`) become an edge only when `peer.service`, `db.namespace` (or `db.name`) or the `server.address` host matches a catalog service; otherwise they are dropped, not listed as unresolved.
- Everything else (INTERNAL, other messaging systems) is skipped.
- Source = the span's `service.name`. Target = `peer.service`, else the host of `server.address` (port stripped). Both are matched against catalog service names, case-insensitively.
- If the target is an IP or unknown host, Topology waits for the matching SERVER span (same trace, `parentSpanId` = the client's `spanId`) and takes the target from that span's `service.name`. Unmatched halves are kept in `topology.pending_span_links` for 5 minutes.

### Configuration reference

| Setting | Where | Default | Meaning |
|---------|-------|---------|---------|
| `CARTOGRA_TENANT_ID` | collector container env (`docker-compose.dev.yml`) | `unset` | Tenant UUID stamped on spans that don't already carry `cartogra.tenant.id`. `unset` is a placeholder: the collector's `filter/no-self` drops `unset` spans before Kafka, so they never reach Topology or the DLQ. Read only at container start. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | each service | `http://localhost:4318` | Where services send OTLP/HTTP. Already points at the dev collector. |
| `KAFKA_BOOTSTRAP_SERVERS` | Topology | `localhost:9092` | Same Kafka the collector exports to (`kafka:29092` inside the compose network). |
| `topology.span-links.ttl` | Topology `application.yml` | `PT5M` | How long an unmatched CLIENT/SERVER half waits for its partner. |
| `topology.span-links.messaging-ttl` | same | `PT24H` | How long a topic's producer/consumer row lives without a new span. A producer and consumer only join if both were seen within this window. |
| `topology.span-links.reap-interval` | same | `PT1M` | How often expired rows are deleted. |
| `topology.span-links.max-unresolved-per-tenant` | same | `1000` | Cap on distinct unknown peer names recorded per tenant. |
| `topology.span-topics.retention` | same (`TOPOLOGY_SPAN_TOPICS_RETENTION`) | `PT1H` | Kafka retention of `cartogra.observability.spans`. Topology reads records within seconds. |
| `topology.span-topics.dlq-retention` | same (`TOPOLOGY_SPAN_TOPICS_DLQ_RETENTION`) | `P3D` | Kafka retention of `cartogra.observability.spans.dlq`. |

Topology creates `cartogra.observability.spans` and `.dlq` at startup with the retentions above, and fixes the retention in place if the broker auto-created them first (`spring.kafka.admin.modify-topic-configs`). The collector also drops spans from Cartogra's own `topology`, `gateway`, `registry` and `ingestion` services.

### Procedure

1. **Start the infrastructure and Topology.** Kafka must be healthy before the collector (the compose file enforces it). Restart Topology after pulling so Flyway applies `V011__observed_edges.sql`.
2. **Register two services** in the UI (Catalog), e.g. `checkout` and `payments`.
3. **Find your tenant id** (`tenant_id` column, not `id`):
   ```bash
   docker exec cartogra-postgres psql -U cartogra -d cartogra -c "select tenant_id, name from registry.tenants"
   ```
   Adjust user/database to your `.env` if different. The JWT `tid` claim is the same value.
4. **Recreate the collector with the tenant** (a plain restart keeps the old env):
   ```bash
   CARTOGRA_TENANT_ID=<tenant-uuid> docker compose -f infra/docker-compose/docker-compose.dev.yml \
     up -d --force-recreate otel-collector
   docker logs cartogra-otel-collector --tail 20   # expect "Everything is ready"
   ```
   Put `CARTOGRA_TENANT_ID` in your `.env` to avoid retyping it.
5. **Send a test span.** The fixture `services/topology/src/test/resources/otel/client-peer-service.json` is an OTLP/JSON body with placeholders: `__SOURCE__` (caller `service.name`), `__TARGET__` (`peer.service`), `__TENANT__`, `__TRACE__` (32 hex), `__CLIENT_SPAN__` (16 hex).
   ```bash
   sed -e "s/__TENANT__/<tenant-uuid>/" -e "s/__SOURCE__/checkout/" -e "s/__TARGET__/payments/" \
       -e "s/__TRACE__/$(openssl rand -hex 16)/" -e "s/__CLIENT_SPAN__/$(openssl rand -hex 8)/" \
       services/topology/src/test/resources/otel/client-peer-service.json \
     | curl -s -i -X POST localhost:4318/v1/traces -H 'Content-Type: application/json' -d @-
   ```
   `200` with `{"partialSuccess":{}}` means the collector accepted it. The span carries its own tenant, so step 4 is only needed for spans from real services.
6. **Verify.** Wait ~10 s (collector batch ≤5 s + graph view refresh ≤5 s), then open `/graph?type=OBSERVED`: one `checkout → payments` edge and no "No observed dependencies yet" alert. Blast radius and SPOF analysis include observed edges.
   ```sql
   select d.dependency_type, d.protocol, d.last_seen_at
   from topology.dependencies d where d.dependency_type = 'observed' and d.deleted_at is null;
   ```

### Other scenarios

| Scenario | How | Expected |
|----------|-----|----------|
| Unknown target | `__TARGET__` = a name not in the catalog | no edge; row in `topology.unresolved_span_services` (`seen_count` grows) |
| IP target + SERVER half | send `client-ip-target.json` (`__SOURCE__`, `__TENANT__`, `__TRACE__`, `__CLIENT_SPAN__`), then `server-child.json` (`__TARGET__` = the server's `service.name`, `__SERVER_SPAN__` 16 hex, **same** `__TENANT__`/`__TRACE__`/`__CLIENT_SPAN__` values) | edge created; `topology.pending_span_links` empty again |
| Kafka edge | send `messaging-endpoint.json` twice with the same `__DESTINATION__` (e.g. `orders.paid`) and `__SYSTEM__=kafka`: once `__KIND__=4` (producer, `__SOURCE__=checkout`), once `__KIND__=5` (consumer, `__SOURCE__=payments`); any order, separate traces | `checkout → payments` edge with protocol `kafka`; 2 rows stay in `topology.pending_span_links` |
| DB edge | register a service named after the DB (e.g. `ledger-db`), send `db-client.json` with `__TARGET__=ledger-db` (the `db.namespace`) | `__SOURCE__ → ledger-db` edge with protocol `db`; an unmatched `__TARGET__` creates nothing |
| Missing tenant | send `no-tenant.json` with the collector started without `CARTOGRA_TENANT_ID` | dropped by the collector (stamped `unset`): nothing on `cartogra.observability.spans` or `.dlq` |
| Invalid/unknown tenant | send `client-peer-service.json` with `__TENANT__` = `not-a-uuid` or a random UUID | record on `cartogra.observability.spans.dlq`, Topology logs `Span batch quarantined: TENANT_MALFORMED` / `TENANT_UNKNOWN` |
| Real traffic | set `OTEL_EXPORTER_OTLP_ENDPOINT` on your own instrumented services | edges for each CLIENT span whose peer is in the catalog |

### Inspecting each hop

There is no collector UI. Use:

| What | How |
|------|-----|
| Collector accepted the span | HTTP `200` from `curl`; `docker logs cartogra-otel-collector` |
| Span reached Kafka | Kafka UI `http://localhost:8086` → topic `cartogra.observability.spans` |
| Span was rejected | Kafka UI → `cartogra.observability.spans.dlq`; header `kafka_dlt-exception-message` holds the reason |
| Topology outcome | counters (below) via Prometheus `http://localhost:9090`; tables `dependencies`, `unresolved_span_services`, `pending_span_links` |

Counters: `cartogra_spans_edges_total{result}` (created/updated/unchanged), `cartogra_spans_skipped_total{reason}` (unsupported_protocol, unresolved_source, unresolved_target, unresolved_db, self), `cartogra_spans_fallback_matched_total`, `cartogra_spans_messaging_matched_total`, `cartogra_spans_quarantined_total{reason}`.

### Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| `TENANT_MALFORMED` in the Topology log | Record has a non-UUID `cartogra.tenant.id` (`unset` is dropped by the collector and never gets here). Old records stay on the DLQ until its 3-day retention. |
| Spans from your own services never arrive | The collector was started without `CARTOGRA_TENANT_ID`, so they are stamped `unset` and dropped (recreate it, step 4). |
| `TENANT_MISSING` | Span has no `cartogra.tenant.id` and the collector didn't add one (spans sent straight to Kafka, or a custom collector config). |
| Collector exits on start | `docker logs cartogra-otel-collector`. An empty `CARTOGRA_TENANT_ID` fails validation (compose defaults it to `unset`); Kafka must be healthy. |
| `200` from curl but no edge | Check Kafka UI for the message. Then `unresolved_span_services` (name not in catalog), then `cartogra_spans_skipped_total` (kind/protocol/self). Source and target must differ and both be live catalog services. |
| Edge in the DB but not in the graph | The view refreshes at most every 5 s; check `dependency_graph_view` and that the UI is on the **Observed** tab. |
| IP-target edge never appears | The SERVER span must share the trace id and have `parentSpanId` = the client's `spanId`, and arrive within 5 minutes. |

### Cleanup

Test edges persist in the dev database. Soft-delete them:

```sql
update topology.dependencies set deleted_at = now()
where dependency_type = 'observed' and deleted_at is null;
delete from topology.pending_span_links;
update topology.unresolved_span_services set deleted_at = now() where deleted_at is null;
```

`demo-flow.spec.ts` (Playwright) writes to this same database. Its observed-edge step posts a span to `localhost:4318` and only runs with `E2E_OBSERVED=true` (collector up, as above); CI leaves it off because the deployable compose has no collector until roadmap 4.4.

### Limitations

- `cartogra.tenant.id` is **unauthenticated**: anyone who can reach the collector or the Kafka topic can attribute spans to any tenant UUID that has live nodes. Keep ports 4317/4318 on localhost.
- One collector = one tenant. Multi-tenant intake and the Settings → Connections → OpenTelemetry card (currently an inactive placeholder) arrive with roadmap 5.9 (`X-Cartogra-Api-Key`, scope `spans:write`).
- Cartogra's own services mostly emit no CLIENT spans yet (roadmap 7.9), so the dev stack won't produce edges by itself; use the fixtures or your own instrumented services.

## Service Auto-Discovery

### Namespace label contract (Kubernetes)

Cartogra discovers K8s Services in namespaces bearing the label:

```yaml
cartogra.io/tenant-id: <UUID>
```

Set this label on any namespace you want Cartogra to monitor:

```bash
kubectl label namespace my-app cartogra.io/tenant-id=<your-tenant-uuid>
```

The KubernetesWorker must be enabled (`ingestion.workers.k8s.enabled=true`) and a synthetic
connection UUID must be configured (`ingestion.workers.k8s.connection-id=<UUID>`).

### Health status mapping

| Endpoints state                                      | Cartogra healthStatus |
| ---------------------------------------------------- | --------------------- |
| Resource absent or no subsets                        | UNKNOWN               |
| All subsets have ready addresses only                | HEALTHY               |
| Some subsets have both ready and not-ready addresses | DEGRADED              |
| All subsets have only not-ready addresses            | UNHEALTHY             |

### Tech-stack detection rules (SCM)

Cartogra probes each non-archived repository via the SCM API for these files:

| File                                          | Technology detected |
| --------------------------------------------- | ------------------- |
| `pom.xml`                                     | java                |
| `pom.xml` containing `spring-boot`            | spring-boot         |
| `build.gradle` or `build.gradle.kts`          | java                |
| `build.gradle*` containing `spring-boot`      | spring-boot         |
| `package.json`                                | javascript          |
| `go.mod`                                      | go                  |
| `Cargo.toml`                                  | rust                |
| `requirements.txt`                            | python              |
| `Dockerfile` FROM `eclipse-temurin:*`         | java                |
| `Dockerfile` FROM `node:*`                    | javascript          |
| `Dockerfile` FROM `python:*`                  | python              |
| `Dockerfile` FROM `golang:*`                  | go                  |
| `Dockerfile` FROM `rust:*`                    | rust                |

Detection is additive: multiple signals result in a list (e.g., `["java","spring-boot"]`).

## To remove the database container + volume and recreate it

```bash
# Run from repo root
docker compose -f infra/docker-compose/docker-compose.dev.yml stop postgres
docker compose -f infra/docker-compose/docker-compose.dev.yml rm -f postgres
docker volume rm cartogra-dev_postgres-data
docker compose -f infra/docker-compose/docker-compose.dev.yml up -d postgres
```
