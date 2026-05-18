# Backend Rules

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
- Scoped Values (JEP 506): use `ScopedValue` instead of `ThreadLocal` for read-only context; immutable, auto-cleanup, virtual-thread safe
- Virtual threads: `spring.threads.virtual.enabled=true` — use for ALL I/O-bound work; NEVER pool virtual threads; CPU-bound uses `ForkJoinPool`
- Module import declarations: `import module java.base;` to reduce import verbosity (JEP 511)
- Flexible constructor bodies (JEP 513): code allowed before `super()` call

**Preview — NEVER use without `--enable-preview`:**

- Structured concurrency (`StructuredTaskScope`) — 5th preview JEP 505
- Primitive types in patterns (`instanceof int i`) — 3rd preview JEP 507

**Removed — NEVER use:**

- String templates (`STR."Hello \{name}"`) — withdrawn Java 23; use `String.format()` or `StringBuilder`
- Security Manager — removed Java 24
- Graal JIT (`-XX:+UseJVMCICompiler`) — removed Java 25; use default C2

**Naming:**

- `UPPER_SNAKE_CASE` constants · `PascalCase` classes · `camelCase` verb-first methods · `lowercase.dot` packages (max 5 levels)
- Checked exceptions = recoverable (IO, network); unchecked = programming errors — NEVER swallow silently
- Use try-with-resources for all `Closeable`

## Spring Boot 4.0

**Requirements:** Java 21+ (25 recommended) · Spring Framework 7.x · Jakarta EE 11 · Servlet 6.1 · Gradle 9.5.0+

- Constructor injection ONLY — NEVER `@Autowired` field injection
- Use explicit constructors for all Spring beans
- Grouped config: `@ConfigurationProperties(prefix = "app.x")` + `@Validated` — NEVER `@Value` for multi-property groups
- Config validation: JSR-380 (`@NotNull`, `@NotEmpty`, `@Email`, `@Positive`, `@Size`); nullable: `org.jspecify.annotations.Nullable` — NEVER `org.springframework.lang.Nullable` (removed)
- Global error handling: `@RestControllerAdvice` — NEVER expose stack traces to clients
- OTel: add `spring-boot-starter-opentelemetry` for OTLP metrics + traces — NEVER manually configure Micrometer tracing bridge
- Actuator: expose ONLY `health,metrics,info` — NEVER `*` in production
- Actuator security: Spring Security 7 secures all actuator endpoints except `/health` by default
- Health probes: `management.endpoint.health.probes.enabled=true`; paths: `/actuator/health/live` and `/actuator/health/ready`
- NEVER add a `/ping` endpoint — use `/actuator/health/live` to verify a service is reachable; Kubernetes liveness/readiness probes already cover this
- Health details: `show-details: when-authorized` — NEVER `always` in production
- Profiles: `application-{env}.yml`; secrets via `${ENV_VAR:default}` — NEVER hardcoded values
- Schema: Flyway owns DDL — NEVER `ddl-auto: create-drop` or `update`; add `spring-boot-starter-flyway` explicitly
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

## Spring Data JDBC

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
- Webhook endpoints (GitHub, AzureDevOps, Jira): NEVER wrap in envelope

## OTel & Tracing

- OTel MUST be enabled on EVERY JVM service
- Propagate W3C `traceparent` header to ALL outbound HTTP calls
- Include `traceparent` as a Kafka message header on EVERY producer send
- `X-Trace-Id` response header = body `traceId` = OTel trace ID (32 hex, no dashes)
- Structured JSON logs MUST include `traceId` on every log line
- traceId extraction: `Span.current().getSpanContext().getTraceId()`

## Multi-Tenancy

- `tenant_id UUID NOT NULL` on EVERY domain table — no exceptions
- Gateway MUST inject `X-Tenant-Id` from the validated token; strip it from inbound client requests
- PostgreSQL RLS policy on ALL tenant tables as a safety net
- Redis keys: prefix always `tenant:{tenantId}:...`
- All repository queries MUST filter by `tenant_id`; never return cross-tenant data

## Database & Flyway

- Flyway only — each service owns `src/main/resources/db/migration/V00N__description.sql`
- Next version number: zero-padded 3-digit (V001, V002, ...) — check existing files before creating
- PKs: `UUID DEFAULT gen_random_uuid()`
- Timestamps: `TIMESTAMPTZ` always — NEVER `TIMESTAMP` without timezone
- Soft deletes: `deleted_at TIMESTAMPTZ` — NEVER execute `DELETE` on domain rows
- JSONB for flexible metadata; add GIN index: `CREATE INDEX ON t USING GIN (metadata)`
- Table and column names: `snake_case`
- Recursive CTEs for graph queries (blast radius, cycle detection, ancestry)

## Kafka

Every message payload MUST match this envelope:

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

## Internal Service Communication (Gateway → Services)

The Gateway calls downstream services via REST using Spring's `RestClient`. This is the only approved synchronous inter-service communication mechanism. gRPC is deferred to Phase 6 research.

**Rules:**
- Inject a configured `RestClient` bean per downstream service (base URL from env var)
- Propagate the W3C `traceparent` header on every outbound request
- Forward the gateway-derived `X-Tenant-Id` header on every call
- Catch HTTP errors and map them to domain exceptions at the infrastructure boundary — never let `RestClientException` reach the REST layer
- OTel auto-instrumentation covers `RestClient` via the Spring Boot starter — no manual interceptor required

```java
// infrastructure/client/RegistryClient.java
@Component
public class RegistryClient {

    private final RestClient restClient;

    public RegistryClient(@Value("${services.registry.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public ServiceDto getService(UUID tenantId, UUID serviceId) {
        return restClient.get()
            .uri("/services/{id}", serviceId)
            .header("X-Tenant-Id", tenantId.toString())
            .retrieve()
            .onStatus(HttpStatusCode::isError, (req, res) -> {
                throw new InfrastructureException("registry call failed: " + res.getStatusCode());
            })
            .body(new ParameterizedTypeReference<ApiResponse<ServiceDto>>() {})
            .data();
    }
}
```

Configure base URLs in `application.yml`:

```yaml
services:
  registry:
    base-url: ${REGISTRY_BASE_URL:http://localhost:8081}
  topology:
    base-url: ${TOPOLOGY_BASE_URL:http://localhost:8082}
```

## Auth

- Browser clients: httpOnly JWT cookie
- Non-browser clients: `Authorization: Bearer <token>`
- CI / automation: `X-Cartogra-Api-Key: <key>` (tenant-scoped API key) — NEVER HMAC v1
- Gateway issues ALL tokens — NEVER a separate auth microservice (MVP scope)
- Gateway strips `X-Tenant-Id` from inbound requests and injects it from the validated token
- JWT payload: `sub` (userId), `tid` (tenantId), `roles[]`, `exp`
