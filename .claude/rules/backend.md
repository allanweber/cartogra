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

## Application Layering (flat — no over-engineering)

Five top-level packages per service — NEVER an `application` package, NEVER `api/controller`+`api/dto` or `application/port/out` nesting:

| Package | Holds |
|---------|-------|
| `api` | controllers + `GlobalExceptionHandler`; request/response records in `api/dto` |
| `domain` | domain records, the `@Service` classes that operate on them, `domain/event`, `domain/exception`, value/payload records |
| `config` | `@Configuration`, `@ConfigurationProperties`, filters |
| `repository` | repository interfaces + repo-input value objects (e.g. `ServiceFilter`) |
| `infrastructure` | Kafka, schedulers, SCM/HTTP clients, security, `infrastructure/jdbc` Jdbc repository implementations — and each outbound provider-port interface (SCM provider, health checker, email sender) lives here beside its adapter |

- Flow: `api` → `domain` (services) → `repository` + `infrastructure`
- ONE `@Service` class per domain concept, in `domain` next to the records it operates on — NEVER `*UseCase`/`*UseCaseImpl` interface+impl pairs, NEVER command DTOs, NEVER a `*Mapper` class
- Controllers inject ONLY services — NEVER a repository/producer/client directly in a controller
- Interfaces (ports) ONLY where a field needs a real seam (repositories, providers, locks); a once-implemented service gets NO interface. Repository ports go in `repository` (their Jdbc impls in `infrastructure/jdbc`); other provider ports go in `infrastructure` beside their adapter
- Domain → response via `static from(domain)` on the response record (in `api/dto`); ONE nullable request record per entity (service enforces create-time requireds)
- See `patterns.md` → "Layering" + "REST Endpoint" for the skeleton

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

The Gateway proxies to downstream services declaratively via Spring Cloud Gateway (webmvc) routes — there is no per-service `RestClient` client class. This is the only approved synchronous inter-service communication mechanism. gRPC is deferred to Phase 6 research. See ADR-0024.

**Rules:**
- Declare each downstream as a route in `application.yml` (base URL from env var, path predicate)
- Attach a Resilience4j `CircuitBreaker` gateway filter to every route — instance name identical to the route id (e.g. `registry`, `ingestion`)
- Count 5xx responses AND connection exceptions/timeouts as breaker failures
- On open breaker / call failure, forward to a fallback route that throws `ServiceUnavailableException` (carries the downstream service name) — mapped by `GlobalExceptionHandler` to HTTP 503 + `ErrorCodes.SERVICE_UNAVAILABLE`, never a raw proxy error
- Expose breaker state under `/actuator/health` as a detail only — an open breaker must NOT flip the aggregate status (the gateway is healthy; failing readiness would pull it from the LB and worsen the incident)
- W3C `traceparent` propagation and `X-Tenant-Id` forwarding are handled by the existing filter chain (`GlobalTracingFilter`, `TenantInjectionFilter`) — no per-route configuration needed
- OTel auto-instrumentation covers Spring Cloud Gateway routes via the Spring Boot starter — no manual interceptor required

```yaml
# application.yml
spring:
  cloud:
    gateway:
      server:
        webmvc:
          routes:
            - id: registry
              uri: ${REGISTRY_URI:http://localhost:8081}
              predicates:
                - Path=/api/v1/registry/**
              filters:
                - name: CircuitBreaker
                  args:
                    id: registry
                    fallbackPath: /internal/fallback/registry
                    statusCodes: 500,501,502,503,504

resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
    instances:
      registry:
        base-config: default
```

Notes on gotchas hit wiring this up: the `CircuitBreaker` filter's `args` bind to bean properties, so the keys are `id`/`fallbackPath`/`statusCodes` (not `name`/`fallbackUri` — those don't bind and fail silently or error at startup); `statusCodes` takes numeric HTTP codes only, not series names like `SERVER_ERROR`. A bare `instances.<name>: {}` with no `base-config` does NOT inherit `configs.default` in practice — set `base-config: default` explicitly per instance. `resilience4j-spring-boot3`'s own `CircuitBreakersHealthIndicatorAutoConfiguration` (and its `register-health-indicator`/`allow-health-indicator-to-fail` properties) silently never activates on Spring Boot 4 — it's `@ConditionalOnClass`-gated on the old `org.springframework.boot.actuate.health.HealthIndicator`, which moved to `org.springframework.boot.health.contributor.HealthIndicator` in the Boot 4 actuator restructuring. Write a plain `HealthIndicator` bean against the new package instead (see below) — don't rely on the resilience4j starter for this until it ships Boot 4 support.

```java
// infrastructure/health/CircuitBreakersHealthIndicator.java — Boot 4's new health package, always UP
@Component("circuitBreakersHealthIndicator")
public class CircuitBreakersHealthIndicator implements HealthIndicator {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CircuitBreakersHealthIndicator(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        for (CircuitBreaker cb : circuitBreakerRegistry.getAllCircuitBreakers()) {
            details.put(cb.getName(), Map.of("circuitBreakerState", cb.getState().name()));
        }
        return Health.up().withDetails(details).build(); // always UP — state is a detail, never drags down the aggregate
    }
}
```

```java
// api/CircuitBreakerFallbackController.java
@RestController
public class CircuitBreakerFallbackController {

    @RequestMapping("/internal/fallback/{service}")
    public void fallback(@PathVariable String service) {
        throw new ServiceUnavailableException(service);
    }
}
```

New route for a not-yet-built service (topology/contract/intelligence): add the route + a same-named breaker instance together — don't route to a service with no breaker.

## Auth

- Browser clients: httpOnly JWT cookie
- Non-browser clients: `Authorization: Bearer <token>`
- CI / automation: `X-Cartogra-Api-Key: <key>` (tenant-scoped API key) — NEVER HMAC v1
- Gateway issues ALL tokens — NEVER a separate auth microservice (MVP scope)
- Gateway strips `X-Tenant-Id` from inbound requests and injects it from the validated token
- JWT payload: `sub` (userId), `tid` (tenantId), `roles[]`, `exp`
