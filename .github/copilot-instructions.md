# Cartogra — Copilot Instructions

> Dense reference for AI assistants. Apply every rule here to all generated code. No exceptions.

---

## Identity & Service Map

**Product**: Cartogra — living service registry + dependency intelligence platform
**Style**: Multi-tenant SaaS, hexagonal architecture per service, event-driven via Kafka

| Service | Port | Role |
|---------|------|------|
| `gateway` | 8080 | Auth, routing, tenant injection, rate limiting |
| `registry` | 8081 | Service CRUD, team ownership, API contracts |
| `topology` | 8082 | Dependency graph, blast radius, cycle detection |
| `contract` | 8083 | Contract validation, breaking-change detection |
| `intelligence` | 8084 | Claude API integration, NL queries, health score |
| `ingestion` | 8085 | SCM/K8s sync workers, webhook receivers, spec discovery |
| `frontend` | 3000 | TanStack Start web app |

---

## Tech Stack (Non-Negotiable)

| Layer | Use | NEVER use |
|-------|-----|-----------|
| Persistence | `spring-boot-starter-data-jdbc` + PostgreSQL | JPA / Hibernate / `EntityManager` |
| Migrations | Flyway (per-service) | Liquibase, `ddl-auto` |
| Messaging | Apache Kafka | RabbitMQ, SQS |
| Internal RPC | gRPC (`spring-grpc` 1.x, protobuf) | REST between services, Feign, `RestClient` for internal calls |
| Cache / Rate-limit | Redis | Memcached |
| Graph queries | Hand-written SQL + recursive CTEs | Graph DB, Neo4j |
| Tracing | OpenTelemetry (OTLP) | Zipkin client, Sleuth |
| Auth tokens | JWT (gateway-issued) | Per-service token issuance |
| Email | Resend | SendGrid, SES directly |
| AI | Cheapest Claude model | N/A |
| Frontend framework | TanStack Start | Next.js, Remix, custom SSR |
| Frontend routing | TanStack Router (file-based) | React Router, reach/router |
| Frontend state | Zustand | Redux, Context for global state |
| Frontend data | TanStack Query | SWR, Apollo, raw fetch |
| Frontend tables | TanStack Table | AG Grid, MUI Data Grid |
| Frontend forms | TanStack Forms | Formik, Redux Form |
| Frontend UI | shadcn/ui + Tailwind | MUI, Ant Design, Bootstrap |
| Frontend graphs | D3 | Cytoscape, vis.js |
| Frontend charts | Recharts | Chart.js |
| Frontend package mgr | pnpm | npm, yarn |

---

## Critical Rules — DO

- Add `tenant_id UUID NOT NULL` to EVERY new domain table — no exceptions
- Wrap ALL Spring REST responses in the envelope (except webhook receivers)
- Propagate OTel `traceparent` to ALL downstream HTTP calls, gRPC calls, and Kafka messages
- Use gRPC for ALL direct service-to-service synchronous calls
- `.proto` files live exclusively in `shared:contracts` — never inside a service module
- Use constructor injection in ALL Spring beans
- Set resource requests AND limits on EVERY K8s container
- Use `for_each` (not `count`) in Terraform for removable resources
- Write a Flyway migration for every schema change
- Use `pnpm` for all frontend package operations (`pnpm install`, `pnpm add`, `pnpm run`)

---

## Critical Rules — NEVER

- Add JPA / Hibernate to any service
- Add Spring dependencies to `shared:common` (plain Java only)
- Return `null` from a method — use `Optional<T>` or throw
- Hard-delete rows — use `deleted_at TIMESTAMPTZ` soft delete
- Use `@Autowired` field injection
- Use image tag `latest` in Dockerfiles or K8s manifests
- Hardcode credentials anywhere
- Expose actuator `*` in production
- Concatenate SQL strings — always use named params
- Run `terraform destroy` in CI without a human approval gate
- Use REST/HTTP for direct service-to-service calls — use gRPC
- Use deprecated APIs or `@SuppressWarnings` — fix the root cause instead
- Commit or push without running the full build + tests first and confirming they pass

---

## Java 25

**Use freely (finalized):**
- Records for immutable DTOs — NEVER mutable POJO where a record fits
- `Optional<T>` as return type when value may be absent — NEVER return `null`
- NEVER use `Optional` as a field, parameter, or in collections — only at method return boundary
- NEVER call `Optional.get()` without `isPresent()`; use `orElseThrow()` / `orElse()`
- `final` fields and immutable collections by default
- Unnamed variables: `_` in catch (`catch (IOException _)`), lambdas, record destructuring (JEP 456)
- Pattern matching for switch — exhaustive `switch` with records and sealed types (JEP 441)
- Record patterns — deconstruct records in `instanceof` and `switch` (JEP 440)
- Scoped Values (JEP 506): `ScopedValue` instead of `ThreadLocal` for read-only context
- Virtual threads: `spring.threads.virtual.enabled=true` — use for ALL I/O-bound work; NEVER pool virtual threads
- Module import declarations: `import module java.base;` (JEP 511)
- Flexible constructor bodies (JEP 513): code before `super()` call

**Preview — NEVER use without `--enable-preview`:**
- Structured concurrency (`StructuredTaskScope`) — JEP 505
- Primitive types in patterns (`instanceof int i`) — JEP 507

**Removed — NEVER use:**
- String templates (`STR."Hello \{name}"`) — withdrawn Java 23; use `String.format()` or `StringBuilder`
- Security Manager — removed Java 24
- Graal JIT (`-XX:+UseJVMCICompiler`) — removed Java 25

**Naming:** `UPPER_SNAKE_CASE` constants · `PascalCase` classes · `camelCase` verb-first methods · `lowercase.dot` packages (max 5 levels)

---

## Spring Boot 4.0

**Requirements:** Java 21+ (25 recommended) · Spring Framework 7.x · Jakarta EE 11 · Servlet 6.1 · Gradle 9.5.0+

- Constructor injection ONLY — NEVER `@Autowired` field injection
- Grouped config: `@ConfigurationProperties(prefix = "app.x")` + `@Validated`
- Nullable: `org.jspecify.annotations.Nullable` — NEVER `org.springframework.lang.Nullable` (removed)
- Global error handling: `@RestControllerAdvice` — NEVER expose stack traces to clients
- OTel: `spring-boot-starter-opentelemetry` for OTLP metrics + traces
- Actuator: expose ONLY `health,metrics,info` — NEVER `*` in production
- Health probes: `management.endpoint.health.probes.enabled=true`; paths: `/actuator/health/live` and `/actuator/health/ready`
- Health details: `show-details: when-authorized` — NEVER `always` in production
- Schema: Flyway owns DDL — NEVER `ddl-auto: create-drop` or `update`

**Jackson 3 (bundled in Spring Boot 4):**
- `@JsonComponent` → `@JacksonComponent`; `@JsonMixin` → `@JacksonMixin`
- Group ID: `com.fasterxml.jackson` → `tools.jackson` — NEVER import `com.fasterxml.jackson` in new code

**Testing (Spring Boot 4):**
- `@MockBean` → `@MockitoBean`; `@SpyBean` → `@MockitoSpyBean`
- `@SpringBootTest` requires `@AutoConfigureMockMvc` and `@AutoConfigureTestRestTemplate` explicitly

**Removed:**
- `spring-boot-starter-aop` → use `spring-boot-starter-aspectj`

---

## Spring Data JDBC

- One `Repository` per aggregate root — route child entity saves through root (NEVER save children directly)
- Cross-aggregate references: store IDs only — NEVER store object references across aggregates
- NEVER attempt lazy loading — JDBC loads eagerly; design small focused aggregates
- Custom queries: `@Query` with named params (`:param`) or `NamedParameterJdbcTemplate`
- Pagination: extend `PagingAndSortingRepository`; NEVER unbounded queries on large tables

---

## HTTP Response Envelope

ALL Spring REST endpoints **except** webhook receivers MUST use this envelope.

**Success (2xx):**
```json
{ "data": "<T>", "traceId": "a3f1c8d2...exactly32lowercasehex" }
```
Header: `X-Trace-Id: <same 32 hex chars>`

**Error (4xx / 5xx):**
```json
{
  "error": { "code": "RESOURCE_NOT_FOUND", "message": "...", "details": {} },
  "traceId": "a3f1c8d2...exactly32lowercasehex"
}
```
Header: `X-Trace-Id: <same 32 hex chars>`

Rules:
- `traceId` = `Span.current().getSpanContext().getTraceId()` — exactly 32 lowercase hex chars, no dashes
- Webhook endpoints (GitHub, AzureDevOps, Jira): NEVER wrap in envelope

```java
// Reusable records (shared:common or per-service api/)
public record ApiResponse<T>(T data, String traceId) {}
public record ErrorResponse(ErrorDetail error, String traceId) {}
public record ErrorDetail(String code, String message, Object details) {}
```

---

## OTel & Tracing

- OTel MUST be enabled on EVERY JVM service
- Propagate W3C `traceparent` header to ALL outbound HTTP calls
- Include `traceparent` as a Kafka message header on EVERY producer send
- `X-Trace-Id` response header = body `traceId` = OTel trace ID (32 hex, no dashes)
- Structured JSON logs MUST include `traceId` on every log line
- traceId extraction: `Span.current().getSpanContext().getTraceId()`

---

## Multi-Tenancy

- `tenant_id UUID NOT NULL` on EVERY domain table — no exceptions
- Gateway MUST inject `X-Tenant-Id` from the validated token; strip it from inbound client requests
- PostgreSQL RLS policy on ALL tenant tables as a safety net
- Redis keys: prefix always `tenant:{tenantId}:...`
- All repository queries MUST filter by `tenant_id`; never return cross-tenant data
- Extract from header: `@RequestHeader("X-Tenant-Id") UUID tenantId`

---

## Database & Flyway

- Flyway only — each service owns `services/<name>/src/main/resources/db/migration/`
- File naming: `V<NNN>__description.sql` — zero-padded 3-digit, two underscores (e.g., `V001__init.sql`)
- Check existing files before creating — never reuse or skip version numbers
- PKs: `UUID DEFAULT gen_random_uuid()`
- Timestamps: `TIMESTAMPTZ` always — NEVER `TIMESTAMP` without timezone
- Soft deletes: `deleted_at TIMESTAMPTZ` — NEVER execute `DELETE` on domain rows
- JSONB for flexible metadata; add GIN index: `CREATE INDEX ON t USING GIN (metadata)`
- Table and column names: `snake_case`
- Recursive CTEs for graph queries (blast radius, cycle detection, ancestry)

New table template:
```sql
CREATE TABLE <name> (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    -- domain columns --
    metadata    JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);
CREATE INDEX ON <name> (tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX ON <name> USING GIN (metadata) WHERE metadata IS NOT NULL;
ALTER TABLE <name> ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON <name>
    USING (tenant_id = current_setting('app.tenant_id')::UUID);
```

---

## Kafka

Every message payload MUST use this envelope:
```json
{
  "event_id": "<UUIDv5>",
  "event_type": "service.registered",
  "entity_id": "<UUID>",
  "tenant_id": "<UUID>",
  "timestamp": "<ISO-8601>",
  "version": 1,
  "correlation_id": "<UUID>",
  "payload": {}
}
```

- Message key: primary entity UUID (ensures partition ordering per entity)
- Topic naming: `cartogra.{domain}.{entity}.{event}` (e.g., `cartogra.registry.service.registered`)
- Kafka message headers: include `traceparent` (W3C format) on every producer send
- Extract `traceparent` in every consumer before processing
- Introduce new topic ONLY when first real producer/consumer exists — no speculative topics

Producer OTel propagation pattern:
```java
OpenTelemetry otel = GlobalOpenTelemetry.get();
otel.getPropagators().getTextMapPropagator().inject(Context.current(), record.headers(),
    (headers, key, value) -> headers.add(key, value.getBytes(StandardCharsets.UTF_8)));
```

---

## gRPC (Internal Service-to-Service)

All direct synchronous calls between services MUST use gRPC. REST between internal services is NEVER acceptable.

- All `.proto` files live exclusively in `shared:contracts` — NEVER inside a service module
- Proto package: `io.cartogra.{domain}.v{N}`; Java output: `io.cartogra.grpc.{domain}.v{N}`
- `option java_multiple_files = true` on every proto file
- Version proto packages on breaking changes (`registry/v1/` → `registry/v2/`) — NEVER reuse fields

Server:
```java
@GrpcService
public class RegistryGrpcService extends RegistryServiceGrpc.RegistryServiceImplBase {
    // Extract tenant from gRPC metadata via interceptor — NEVER as a proto field
    // Throw StatusRuntimeException with appropriate Status code for errors
}
```

Client:
```java
@Component
public class RegistryGrpcClient {
    private final RegistryServiceGrpc.RegistryServiceBlockingStub stub;
    public RegistryGrpcClient(@GrpcClient("registry") Channel channel) {
        this.stub = RegistryServiceGrpc.newBlockingStub(channel);
    }
    // Attach x-tenant-id metadata on every call
    // Catch StatusRuntimeException and rethrow as domain exception — NEVER propagate to REST layer
}
```

Channel config in `application.yml`:
```yaml
spring:
  grpc:
    client:
      channels:
        registry:
          address: ${REGISTRY_GRPC_HOST:localhost}:${REGISTRY_GRPC_PORT:9091}
```

gRPC server port convention: `${GRPC_PORT:909X}` (separate from REST port `808X`)

---

## Auth

- Browser clients: httpOnly JWT cookie
- Non-browser clients: `Authorization: Bearer <token>`
- CI / automation: `X-Cartogra-Api-Key: <key>` (tenant-scoped API key)
- Gateway issues ALL tokens — NEVER a separate auth microservice (MVP scope)
- Gateway strips `X-Tenant-Id` from inbound requests; injects it from validated token
- JWT payload: `sub` (userId), `tid` (tenantId), `roles[]`, `exp`

---

## Frontend

File-based routing — all routes under `frontend/src/routes/` using `createFileRoute`. NEVER `createBrowserRouter`.

API client — always extract `.data` from success, handle `.error` for errors:
```ts
async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, init)
  const traceId = res.headers.get('X-Trace-Id') ?? 'unknown'
  const body = await res.json()
  if (!res.ok) throw new ApiError(body.error.code, body.error.message, traceId)
  return body.data as T
}
```

Component conventions:
- Named exports only — NEVER `export default` a component
- One component per file; `PascalCase` filename
- TanStack Query (`useQuery`/`useMutation`) for ALL data fetching
- shadcn/ui primitives — NEVER raw HTML where a shadcn component exists
- Tailwind for all layout and spacing — NEVER inline styles
- Always show loading (shadcn `Skeleton`) and error (`Alert` with traceId) states

Route template:
```tsx
export const Route = createFileRoute('/services/')({ component: ServicesPage })
function ServicesPage() {
  return <AppLayout><ServiceList /></AppLayout>
}
```

---

## Dockerfile

```dockerfile
FROM eclipse-temurin:25-jdk-jammy AS builder
WORKDIR /build
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY shared/ shared/
COPY services/<name>/ services/<name>/
RUN --mount=type=cache,target=/root/.gradle ./gradlew :services:<name>:bootJar -x test

FROM eclipse-temurin:25-jre-jammy AS runtime
RUN useradd -m -u 1000 appuser
WORKDIR /app
COPY --from=builder --chown=appuser:appuser /build/services/<name>/build/libs/*.jar app.jar
USER appuser
EXPOSE 808X
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -f http://localhost:808X/actuator/health/live || exit 1
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -XX:MaxRAMPercentage=75 -jar app.jar"]
```

Rules:
- Multi-stage REQUIRED: JDK builder → JRE runtime
- Non-root user REQUIRED: UID 1000
- `-XX:MaxRAMPercentage=75` — NEVER hard-set `-Xmx`/`-Xms`
- COPY only — NEVER `ADD`
- NEVER use image tag `latest`
- HEALTHCHECK points to `/actuator/health/live`
- `.dockerignore` excludes: `.git`, `build/`, `target/`, `*.md`, `.env`, `node_modules`

---

## Kubernetes

- Resource requests AND limits REQUIRED on every container
- Memory limit = memory request (Guaranteed QoS); CPU limit ≈ 2× request
- Three probes REQUIRED: `livenessProbe` → `/actuator/health/live`, `readinessProbe` → `/actuator/health/ready`, `startupProbe` → `/actuator/health/live`
- Pod `securityContext`: `runAsNonRoot: true`, `runAsUser: 1000`, `fsGroup: 1000`
- Container `securityContext`: `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`
- Mount `/tmp` as `emptyDir` when `readOnlyRootFilesystem: true`
- Secrets: mount as volumes — NEVER as env vars; NEVER commit Secret YAMLs to Git
- `PodDisruptionBudget` REQUIRED (`minAvailable: 2`)
- `HorizontalPodAutoscaler`: `minReplicas ≥ PDB.minAvailable`; `scaleDown.stabilizationWindowSeconds: 300`
- Rolling update: `maxUnavailable: 0`, `maxSurge: 1`
- Service type: `ClusterIP` — NEVER `LoadBalancer` for internal services
- Required labels: `app`, `version`, `environment`, `team`
- Namespaces: `dev` · `staging` · `prod` · `infra`

---

## Terraform

- Module structure: `terraform/modules/<name>/` (main.tf + variables.tf + outputs.tf)
- Environment configs: `terraform/environments/<env>/` (main.tf + terraform.tfvars + backend.tf)
- Remote state REQUIRED: S3 backend + DynamoDB lock + `encrypt = true` + bucket versioning
- Separate state per environment
- Sensitive vars: `sensitive = true` — NEVER hardcode passwords/tokens
- NEVER commit `.tfvars` with real secrets
- Tag ALL resources: `Environment`, `Project`, `ManagedBy = "Terraform"`, `Owner`, `CostCenter`
- `for_each` (not `count`) for removable resources
- `validation` blocks on enum variables
- NEVER run `terraform destroy` in CI without a human approval gate

---

## CI / Build

- Gradle multi-module: `./gradlew build` from repo root
- Trivy scan on every PR — block merges with CRITICAL or HIGH CVEs
- Conventional commits: `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:` (keep subject under 72 chars)
- Branch strategy: `main` (protected) · `feat/<name>` · `fix/<name>`
- NEVER bypass hooks with `--no-verify`

---

## Git Workflow

Every task from `docs/execution-checklist.md` follows this flow:

1. **Create a GitHub issue**: title `USx.y — <story title>`, milestone `Phase N — <name>`, label `user-story`
2. **Create branch from main**: `feat/<phase>.<seq>-<short-slug>` (e.g., `feat/0.7-shared-contracts`)
3. **Commit** with conventional commit format
4. **Open PR**: include `Closes #<issue>` in body, link same milestone, CI must be green
5. **Milestone completes** only when all its issues are closed

Branch naming: `feat/<phase>.<seq>-<slug>` or `fix/<phase>.<seq>-<slug>`

---

## Working Conventions

- **Commit/push approval**: NEVER run `git commit` or `git push` without Allan's explicit approval. Show diff summary + draft commit message first, then ask "OK to commit?". No exceptions.
- **Tests before commit**: Always run `./gradlew :services:<name>:test` (or relevant Gradle task) and confirm green BEFORE asking for commit approval.
- **Checklist tracking**: After implementing tasks from `docs/execution-checklist.md`, always update that file to mark completed items `[x]`.
- **No deprecated APIs**: Never use deprecated classes/methods. Never use `@SuppressWarnings` — fix the root cause.
- **Frontend packages**: Use `pnpm` exclusively — `pnpm install`, `pnpm add`, `pnpm run`. CI: `pnpm/action-setup@v4` + `pnpm install --frozen-lockfile`.

---

## File Structure

```
cartogra/
├── services/
│   └── {name}/
│       ├── src/main/java/io/cartogra/{name}/
│       │   ├── api/           # Controllers, request/response DTOs, mappers
│       │   ├── domain/        # Entities, value objects, domain events
│       │   ├── application/   # Use cases, service interfaces
│       │   ├── infrastructure/ # JDBC repos, Kafka producers/consumers, gRPC
│       │   └── config/        # Spring beans, security config, OTel config
│       └── src/main/resources/db/migration/
├── shared/
│   ├── common/                # Plain Java only — ZERO Spring dependencies
│   ├── contracts/             # All .proto files live HERE (never in service modules)
│   └── test-support/          # Testcontainers helpers
├── frontend/                  # TanStack Start app
├── infra/
│   ├── docker/                # Dockerfiles (one per service)
│   ├── k8s/                   # K8s manifests per service
│   └── terraform/
│       ├── modules/
│       └── environments/
└── docs/
    ├── adr/                   # Architecture Decision Records (NNNN-kebab-title.md)
    ├── execution-checklist.md # Authoritative progress tracker — keep [x] up to date
    └── plan.md                # Phase-gated delivery plan
```

---

## Available Prompt Files

Invoke these in Copilot Chat by typing `#<name>` (e.g., `#new-service`):

| Prompt | Purpose |
|--------|---------|
| `#new-service` | Scaffold Spring Boot service + hexagonal structure + Dockerfile |
| `#add-endpoint` | REST endpoint with envelope + OTel + error handler |
| `#add-migration` | Next numbered Flyway migration file |
| `#add-kafka` | Kafka topic config + producer/consumer with traceparent |
| `#add-k8s-manifest` | K8s Deployment + Service + HPA + PDB |
| `#add-terraform-module` | Terraform module skeleton (main / variables / outputs) |
| `#add-adr` | Architecture Decision Record from template |
| `#new-component` | React component with TanStack Query + shadcn + envelope parsing |
| `#new-page` | TanStack Router file-based route/page |
| `#new-feature` | Full-stack feature plan (backend + frontend + migrations + K8s) |
| `#check-constraints` | Audit file/selection against all project rules |
| `#check-docker` | Audit Dockerfile for multi-stage, non-root, JVM flags |
| `#check-k8s` | Audit K8s manifests for resources, probes, security, PDB, HPA |
