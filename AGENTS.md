# Cartogra — Agent Context

> Dense reference for AI agents. No prose — only rules, tables, formats. Read entirely before writing code.

## Identity

- **Product**: Cartogra — living service registry + dependency intelligence platform
- **Monorepo root**: `cartogra/`
- **Style**: Multi-tenant SaaS, hexagonal architecture per service, event-driven via Kafka

## Service Map

| Service | Port | Role |
|---------|------|------|
| `gateway` | 8080 | Auth, routing, tenant injection, rate limiting |
| `registry` | 8081 | Service CRUD, team ownership, API contracts |
| `topology` | 8082 | Dependency graph, blast radius, cycle detection |
| `contract` | 8083 | Contract validation, breaking-change detection |
| `intelligence` | 8084 | Claude API integration, NL queries, health score |
| `frontend` | 3000 | TanStack Start web app |

## Tech Stack (Non-Negotiable)

| Layer | Choice | NEVER use |
|-------|--------|-----------|
| Persistence | `spring-boot-starter-data-jdbc` + PostgreSQL | JPA / Hibernate / `EntityManager` |
| Migrations | Flyway (per-service) | Liquibase, `ddl-auto` |
| Messaging | Apache Kafka | RabbitMQ, SQS |
| Cache / Rate-limit | Redis | Memcached |
| Graph queries | Hand-written SQL + recursive CTEs | Graph DB, Neo4j |
| Tracing | OpenTelemetry (OTLP) | Zipkin client, Sleuth |
| Auth tokens | JWT (gateway-issued) | Per-service token issuance |
| Email | Resend | SendGrid, SES directly |
| AI | Use the cheapest one | N.a |
| Frontend framework | TanStack Start | Next.js, Remix, custom SSR stack |
| Frontend routing | TanStack Router | React Router, reach/router |
| Frontend state | Zustand | Redux, Context for global state |
| Frontend data | TanStack Query | SWR, Apollo, raw fetch |
| Frontend table | TanStack Table | AG Grid, MUI Data Grid, react-data-grid |
| Frontend forms | TanStack Forms | Formik, Redux Form |
| Frontend UI | shadcn/ui + Tailwind | MUI, Ant Design, Bootstrap |
| Frontend graphs | D3 (dependency graph) | Cytoscape, vis.js |
| Frontend charts | Recharts (dashboards) | Chart.js |

## Critical Constraints

**DO:**
- Read this file fully before writing any code
- Add `tenant_id UUID NOT NULL` to EVERY new domain table
- Wrap ALL Spring REST responses in the envelope (except webhook receivers)
- Propagate OTel `traceparent` to ALL downstream HTTP calls and Kafka messages
- Use constructor injection in ALL Spring beans
- Set resource requests AND limits on EVERY K8s container
- Use `for_each` (not `count`) in Terraform for removable resources
- Write a Flyway migration for every schema change

**NEVER:**
- Add JPA / Hibernate to any service
- Add Spring dependencies to `shared:common` (plain Java only)
- Return null from a method — use `Optional<T>` or throw
- Hard-delete rows — use `deleted_at TIMESTAMPTZ` soft delete
- Use `@Autowired` field injection
- Use image tag `latest` in Dockerfiles or K8s manifests
- Hardcode credentials anywhere
- Expose actuator `*` in production
- Concatenate SQL strings — always use named params
- Run `terraform destroy` in CI without a human approval gate

## Java 25 Rules

**Finalized features — use freely:**
- Records for immutable DTOs: `record UserDto(UUID id, String name) {}` — NEVER mutable POJO where a record fits
- `Optional<T>` as return type when value may be absent — NEVER return `null`
- NEVER use `Optional` as a field, parameter, or in collections — only at method return boundary
- NEVER call `Optional.get()` without `isPresent()`; use `orElseThrow()` / `orElse()`
- `final` fields and immutable collections by default
- Naming: `UPPER_SNAKE_CASE` constants · `PascalCase` classes · `camelCase` verb-first methods · `lowercase.dot` packages (max 5 levels)
- Checked exceptions = recoverable (IO, network); unchecked = programming errors — NEVER swallow silently
- Use try-with-resources for all `Closeable`
- Unnamed variables: use `_` in catch (`catch (IOException _)`), lambdas (`list.forEach(_ -> x())`), and record destructuring (`Point(int x, int _)`) — finalized JEP 456
- Pattern matching for switch: use exhaustive `switch` with records and sealed types — finalized JEP 441
- Record patterns: deconstruct records in `instanceof` and `switch` — finalized JEP 440
- Scoped Values (JEP 506): use `ScopedValue` instead of `ThreadLocal` for read-only context; `ScopedValue.where(USER, user).run(...)` — immutable, auto-cleanup, virtual-thread safe; keep `ThreadLocal` only for mutable per-thread cache on long-lived platform threads
- Virtual threads: enabled via `spring.threads.virtual.enabled=true` — use for ALL I/O-bound work; NEVER pool virtual threads; CPU-bound tasks use `ForkJoinPool`; create one per task, don't reuse
- Module import declarations: `import module java.base;` imports all public packages — use to reduce import verbosity (JEP 511)
- Flexible constructor bodies (JEP 513): code allowed before `super()` call — use for pre-validation in subclass constructors

**Still in preview — NEVER use without `--enable-preview`:**
- Structured concurrency (`StructuredTaskScope`) — 5th preview JEP 505; not production-ready
- Primitive types in patterns (`instanceof int i`) — 3rd preview JEP 507; not finalized

**Removed / withdrawn — NEVER use:**
- String templates (`STR."Hello \{name}"`) — withdrawn Java 23; use `String.format()` or `StringBuilder`
- Security Manager (`-Djava.security.manager`) — removed Java 24
- Graal JIT (`-XX:+UseJVMCICompiler`) — removed Java 25; use default C2 compiler

## Spring Boot 4.0 Rules

**Requirements:** Java 21+ (25 recommended) · Spring Framework 7.x · Jakarta EE 11 · Servlet 6.1 · Gradle 8.14+ or 9.x

- Constructor injection ONLY — NEVER `@Autowired` field injection (hidden deps, untestable)
- Use `@RequiredArgsConstructor` (Lombok) for brevity
- Grouped config: `@ConfigurationProperties(prefix = "app.x")` + `@Validated` — NEVER `@Value` for multi-property groups
- Config validation: JSR-380 (`@NotNull`, `@NotEmpty`, `@Email`, `@Positive`, `@Size`); nullable: use `org.jspecify.annotations.Nullable` — NEVER `org.springframework.lang.Nullable` (removed)
- Global error handling: `@RestControllerAdvice` — NEVER expose stack traces to clients
- OTel: add `spring-boot-starter-opentelemetry` for OTLP metrics + traces export; NEVER manually configure Micrometer tracing bridge
- Actuator: expose ONLY `health,metrics,info` — NEVER `*` in production
- Actuator security: Spring Security 7 secures all actuator endpoints except `/health` by default — configure explicit permit for `info,metrics` if needed
- Health probes: liveness + readiness enabled by default (`management.endpoint.health.probes.enabled=true`); probe paths: `/actuator/health/live` and `/actuator/health/ready`
- Health details: `show-details: when-authorized` — NEVER `always` in production
- Profiles: `application-{env}.yml`; secrets via `${ENV_VAR:default}` — NEVER hardcoded values
- Schema: Flyway owns DDL — NEVER `ddl-auto: create-drop` or `update`; add `spring-boot-starter-flyway` explicitly (no longer auto-included)
- RBAC: `@EnableMethodSecurity` + `@PreAuthorize("hasRole('ADMIN')")`

**Jackson 3 (bundled in Spring Boot 4):**
- `@JsonComponent` → `@JacksonComponent`; `@JsonMixin` → `@JacksonMixin`
- Group ID changed: `com.fasterxml.jackson` → `tools.jackson` (except `jackson-annotations`)
- NEVER import `com.fasterxml.jackson` directly in new code

**Testing (Spring Boot 4):**
- `@MockBean` → `@MockitoBean`; `@SpyBean` → `@MockitoSpyBean`
- `@SpringBootTest` no longer auto-provides MockMVC — add `@AutoConfigureMockMvc`
- `@SpringBootTest` no longer provides `TestRestTemplate` — add `@AutoConfigureTestRestTemplate`

**Removed — NEVER use:**
- `spring-boot-starter-aop` → use `spring-boot-starter-aspectj`
- Spring Batch: add `spring-boot-starter-batch-jdbc` explicitly to persist job metadata

## Spring Data JDBC Rules

- One `Repository` per aggregate root — route child entity saves through root (NEVER save children directly)
- Cross-aggregate references: store IDs only — NEVER store object references across aggregates
- NEVER attempt lazy loading — JDBC loads eagerly; design small focused aggregates
- Custom queries: `@Query` with named params (`:param`) or `NamedParameterJdbcTemplate`
- Pagination: extend `PagingAndSortingRepository`; NEVER unbounded queries on large tables

## HTTP Response Envelope

ALL Spring REST endpoints **except** webhook receivers MUST use this envelope.

**Success (2xx):**
```json
{
  "data": "<T>",
  "traceId": "a3f1c8d2...exactly32lowercasehex"
}
```
Header: `X-Trace-Id: <same 32 hex chars>`

**Error (4xx / 5xx):**
```json
{
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "message": "Service with id X not found",
    "details": {}
  },
  "traceId": "a3f1c8d2...exactly32lowercasehex"
}
```
Header: `X-Trace-Id: <same 32 hex chars>`

Rules:
- `traceId` = OTel trace ID, exactly **32 lowercase hex characters** (no dashes)
- Webhook endpoints (GitHub, AzureDevOps, Jira): NEVER wrap in envelope — respond as the upstream expects

## OTel & Tracing Rules

- OTel MUST be enabled on EVERY JVM service (gateway, all domain services, workers)
- Propagate W3C `traceparent` header to ALL outbound HTTP calls
- Include `traceparent` as a Kafka message header on EVERY producer send
- `X-Trace-Id` response header = body `traceId` = OTel trace ID (32 hex chars, no dashes)
- Structured JSON logs MUST include `traceId` on every log line
- traceId extraction: `Span.current().getSpanContext().getTraceId()`

## Multi-Tenancy Rules

- `tenant_id UUID NOT NULL` on EVERY domain table — no exceptions
- Gateway MUST inject `X-Tenant-Id` from the validated token; strip it from inbound client requests
- PostgreSQL RLS policy on ALL tenant tables as a safety net
- Redis keys: prefix always `tenant:{tenantId}:...`
- All repository queries MUST filter by `tenant_id`; never return cross-tenant data

## Database & Flyway Rules

- Flyway only — each service owns `src/main/resources/db/migration/V00N__description.sql`
- Next version number: zero-padded 3-digit (V001, V002, ...) — check existing files before creating
- PKs: `UUID DEFAULT gen_random_uuid()`
- Timestamps: `TIMESTAMPTZ` always — NEVER `TIMESTAMP` without timezone
- Soft deletes: `deleted_at TIMESTAMPTZ` — NEVER execute `DELETE` on domain rows
- JSONB for flexible metadata; add GIN index: `CREATE INDEX ON t USING GIN (metadata)`
- Table and column names: `snake_case`
- Recursive CTEs for graph queries (blast radius, cycle detection, ancestry)

## Kafka Rules

**Every message payload MUST match this envelope:**
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
- Kafka message headers: include `traceparent` (W3C format) on every send
- Introduce new topic ONLY when first real producer/consumer exists — no speculative topics
- Topic naming: `cartogra.{domain}.{entity}.{event}` (e.g., `cartogra.registry.service.registered`)

## Auth Rules

- Browser clients: httpOnly JWT cookie
- Non-browser clients: `Authorization: Bearer <token>`
- CI / automation: `X-Cartogra-Api-Key: <key>` (tenant-scoped API key) — NEVER HMAC v1
- Gateway issues ALL tokens — NEVER a separate auth microservice (MVP scope)
- Gateway strips `X-Tenant-Id` from inbound requests and injects it from the validated token
- JWT payload: `sub` (userId), `tid` (tenantId), `roles[]`, `exp`

## React / Frontend Rules

Tech choices: see stack table above. Behavioral rules:
- File-based routes under `frontend/src/routes` using `createFileRoute` — NEVER `createBrowserRouter`
- Always parse envelope: extract `.data` for success, handle `.error` for errors; NEVER assume flat response
- API client wraps ALL calls; surface `X-Trace-Id` from response headers in error messages
- Docs: [Start](https://tanstack.com/start/latest) · [Query](https://tanstack.com/query/latest) · [Router](https://tanstack.com/router/latest) · [Table](https://tanstack.com/table/latest) · [Forms](https://tanstack.com/forms/latest)

## Dockerfile Rules

- Multi-stage REQUIRED: `builder` stage (`eclipse-temurin:21-jdk-jammy`) → `runtime` stage (`eclipse-temurin:21-jre-jammy`)
- Layer order for cache efficiency: base image → OS deps → create user → copy build descriptors → fetch deps → copy source → build → runtime COPY → USER / ENTRYPOINT
- Non-root user REQUIRED: `RUN useradd -m -u 1000 appuser` → `USER appuser`
- JVM container sizing: `-XX:MaxRAMPercentage=75` — NEVER hard-set `-Xmx`/`-Xms` in container environments
- ENTRYPOINT: `["sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]` (enables signal passthrough for graceful shutdown)
- COPY only — NEVER `ADD`
- `.dockerignore` must exclude: `.git`, `build/`, `target/`, `*.md`, `.env`, `node_modules`
- `HEALTHCHECK CMD curl -f http://localhost:8080/actuator/health/live || exit 1`

## Kubernetes Rules

- Resource requests AND limits REQUIRED on every container — NEVER omit either field
- Sizing: limits ≈ 2× requests; CPU limit throttles (no eviction); memory limit evicts
- Three probes REQUIRED on every Deployment:
  - `livenessProbe` → `/actuator/health/live` (kill + restart if fails)
  - `readinessProbe` → `/actuator/health/ready` (remove from Service endpoints if fails)
  - `startupProbe` → `/actuator/health` (kill if startup exceeds timeout)
- Pod `securityContext`: `runAsNonRoot: true`, `runAsUser: 1000`, `fsGroup: 1000`
- Container `securityContext`: `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`
- Mount `/tmp` as `emptyDir` when `readOnlyRootFilesystem: true`
- Secrets: mount as volumes — NEVER as env vars; NEVER commit Secret YAMLs to Git (use sealed-secrets or external manager)
- `PodDisruptionBudget` REQUIRED for every production Deployment (`minAvailable: 2`)
- `HorizontalPodAutoscaler`: `minReplicas ≥ PDB.minAvailable`; CPU + memory targets; `scaleDown.stabilizationWindowSeconds: 300`
- Rolling update: `maxUnavailable: 0`, `maxSurge: 1` (zero-downtime)
- Service type: `ClusterIP` for internal services; external traffic via Ingress only — NEVER `LoadBalancer` for internal
- Required labels: `app`, `version`, `environment`, `team`
- Namespaces: `dev` · `staging` · `prod` · `infra`
- NEVER `privileged: true`; NEVER `runAsUser: 0`

## Terraform Rules

- Module structure: `terraform/modules/<name>/` (main.tf + variables.tf + outputs.tf + README.md)
- Environment configs: `terraform/environments/<env>/` (main.tf + terraform.tfvars + backend.tf)
- One module per concern (vpc, rds, eks, iam) — NEVER monolith modules
- Remote state REQUIRED: S3 backend + DynamoDB lock table + `encrypt = true` + bucket versioning
- Separate state per environment — NEVER share state files across envs
- Sensitive vars: mark `sensitive = true` — NEVER hardcode passwords/tokens in `.tf` or `.tfvars`
- NEVER commit `.tfvars` with real secrets — use `terraform.tfvars.example` + env vars + secret manager
- Tag ALL resources: `Environment`, `Project`, `ManagedBy = "Terraform"`, `Owner`, `CostCenter`
- Variable validation: `validation { condition = contains([...], var.x) }` for enumerated values
- NEVER edit state files directly — use `terraform state` subcommands

## CI / Build Rules

- Gradle multi-module: `./gradlew build` from repo root — all services build together
- Multi-stage Dockerfile for EVERY deployable JVM service and the frontend
- Trivy scan on every PR — block merges with CRITICAL or HIGH CVEs
- Conventional commits: `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`
- Branch strategy: `main` (protected, PRs only) · `feat/<name>` · `fix/<name>`
- NEVER bypass hooks with `--no-verify`

## File Structure (Key Paths)

```
cartogra/
├── AGENTS.md                              # This file — AI agent context
├── CLAUDE.md                              # Points to AGENTS.md (Claude Code auto-load)
├── services/
│   └── {name}/
│       ├── src/main/java/io/cartogra/{name}/
│       │   ├── api/                       # Controllers, request/response DTOs, mappers
│       │   ├── domain/                    # Entities, value objects, domain events
│       │   ├── application/               # Use cases, service interfaces
│       │   ├── infrastructure/            # JDBC repos, Kafka producers/consumers, HTTP clients
│       │   └── config/                    # Spring beans, security config, OTel config
│       └── src/main/resources/
│           └── db/migration/              # V001__init.sql, V002__add_field.sql ...
├── shared/
│   └── common/                            # Plain Java only — ZERO Spring dependencies
├── frontend/                              # TanStack Start app (React + file-based TanStack Router)
├── infra/
│   ├── docker/                            # Dockerfiles (one per deployable service)
│   ├── k8s/                               # K8s manifests per service + namespace
│   └── terraform/
│       ├── modules/                       # Reusable TF modules (vpc, rds, eks, iam...)
│       └── environments/                  # Per-env root configs (dev/ staging/ prod/)
└── docs/
    └── adr/                               # Architecture Decision Records
```

## Available Skills / Commands

| Command | Purpose |
|---------|---------|
| `/new-service <name>` | Scaffold Spring Boot service + hexagonal structure + Dockerfile |
| `/add-migration <service> <desc>` | Create next numbered Flyway migration for a service |
| `/add-endpoint <method> <path>` | REST endpoint with envelope + OTel + error handler |
| `/add-kafka <topic> <event-type>` | Kafka topic config + producer/consumer boilerplate |
| `/new-component <name>` | React component with TanStack Query + TanStack Table/Forms + shadcn + envelope parsing |
| `/new-page <name> <route>` | TanStack Router file-based route/page with TanStack Query |
| `/add-adr <title>` | Architecture Decision Record from template |
| `/add-k8s-manifest <service>` | K8s Deployment + Service + HPA + PDB with full security context |
| `/add-terraform-module <name>` | Terraform module skeleton (main / variables / outputs) |
| `/check-constraints` | Audit current file/selection against all project rules |
| `/check-docker` | Audit Dockerfile (multi-stage, non-root, JVM flags, layer order) |
| `/check-k8s` | Audit K8s manifests (resources, probes, security, tags, PDB, HPA) |
| `/new-feature <name>` | Full-stack feature plan (backend + frontend + migrations + K8s + tests) |
