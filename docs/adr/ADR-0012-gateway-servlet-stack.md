# ADR-0012 — Gateway Stack Migration from WebFlux to Spring MVC

## Status

Accepted

## Context

The gateway service was originally built on `spring-cloud-gateway-server-webflux` because Spring Cloud Gateway historically required the reactive stack. The entire auth + routing layer was written in reactive style: `WebFilter`, `ServerHttpSecurity`, `ReactiveSecurityContextHolder`, `ReactiveStringRedisTemplate`, `WebClient`, and `Mono<T>` return types throughout controllers and use cases.

Virtual threads (`spring.threads.virtual.enabled=true`) were then enabled globally across all services. With virtual threads, I/O-bound blocking calls (JDBC, Redis, HTTP) are no longer problematic — the JVM scheduler parks the virtual thread and runs other work, achieving the same throughput as reactive I/O without the complexity. The primary motivation for the reactive stack — avoiding thread-per-request I/O stalls — no longer applies.

The reactive stack carried three concrete costs:

1. **Cognitive overhead**: Every mutation in the call chain required understanding whether it was synchronous or deferred, and `Mono.deferContextual()` was needed to propagate OTel trace context correctly through reactive pipelines.
2. **Test complexity**: `WebTestClient` assertion chains were harder to read than `MockMvc`; `MockWebServer` (OkHttp) was required for OAuth provider tests instead of `MockRestServiceServer`.

`spring-cloud-gateway-server-mvc` reached feature parity with the WebFlux edition for the routing features used in Cartogra (path predicate, header rewriting, URI routing). It runs on the standard servlet stack.

## Decision

Migrate the gateway service from `spring-cloud-gateway-server-webflux` to `spring-cloud-gateway-server-mvc` + `spring-boot-starter-web`.

All reactive types are replaced with servlet equivalents:

| Before | After |
|--------|-------|
| `WebFilter` / `GlobalFilter` | `OncePerRequestFilter` |
| `ServerHttpSecurity` | `HttpSecurity` |
| `@EnableWebFluxSecurity` | `@EnableWebSecurity` |
| `ReactiveSecurityContextHolder` | `SecurityContextHolder` |
| `ReactiveStringRedisTemplate` | `StringRedisTemplate` |
| `WebClient` (OAuth providers) | `RestClient` |
| `OkHttpClient` (ResendEmailSender) | `RestClient` |
| `Mono<T>` controller return types | Direct `T` return types |
| `Mono.deferContextual()` for trace ID | `Span.current().getSpanContext().getTraceId()` |
| `WebTestClient` in ITs | `MockMvc` + `@AutoConfigureMockMvc` |
| `MockWebServer` in OAuth tests | Simplified `MockMvc`-only assertions |

The `OkHttp3` dependency is eliminated entirely; all outbound HTTP uses Spring's `RestClient`, consistent with every other service in the monorepo.

Async email sending (previously `Mono.fromRunnable().subscribeOn(boundedElastic()).subscribe()`) uses `CompletableFuture.runAsync()` — virtual threads handle the scheduling transparently.

## Consequences

### Positive

- **Simpler code**: Direct method calls replace reactive chains. Every use case now has a plain Java return type.
- **Aligned with registry and ingestion**: All three JVM services now use the servlet stack, eliminating one source of cross-service inconsistency.
- **Easier tests**: `MockMvc` assertions are more readable than `WebTestClient` chains; `MockRestServiceServer` replaces the heavier `MockWebServer`.
- **One HTTP client**: `RestClient` replaces both `WebClient` and `OkHttp3`, reducing the dependency surface and keeping a single request interceptor pattern for OTel propagation.
- **`opentelemetry-reactor-3.1` dependency removed**: OTel servlet auto-instrumentation (included in `spring-boot-starter-opentelemetry`) handles W3C traceparent propagation for both inbound requests and outbound `RestClient` calls automatically.

### Negative

- **Full filter and security config rewrite required**: Approximately 15 files changed. Risk of regressions in edge cases (cookie path, SameSite attributes, OAuth state management).
- **No streaming support**: If a future gateway feature requires server-sent events or WebSocket proxying, the servlet stack will require a different approach.

### Neutral

- **OTel instrumentation switches transparently**: `opentelemetry-reactor-3.1` → standard servlet instrumentation. No observable change in trace output.
- **Spring Cloud Gateway MVC route configuration syntax**: Routes are defined under `spring.cloud.gateway.server.mvc.routes` instead of `spring.cloud.gateway.server.webflux.routes`. Predicate and filter names are identical.
- **Cookie behaviour unchanged**: `ResponseCookie` (Spring Web) works in both stacks when serialised to a `Set-Cookie` header string via `HttpServletResponse.addHeader`.
