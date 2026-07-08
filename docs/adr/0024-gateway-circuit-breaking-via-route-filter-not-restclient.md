# Gateway circuit breaking is a Resilience4j route filter, not a per-service RestClient wrapper

The gateway proxies to downstream services declaratively via Spring Cloud Gateway (webmvc) routes in `application.yml` — there is no `RegistryClient`/`TopologyClient`/etc. Java class making outbound calls. Adding circuit breakers therefore means attaching a Resilience4j `CircuitBreaker` gateway filter to each route, not wrapping a `RestClient` bean, because no such bean exists for inter-service calls (the gateway's only `RestClient` usages are external: Google/GitHub OAuth, Resend email).

This directly supersedes the "Internal Service Communication" section of `backend.md`, which documented a `RestClient`-per-downstream-service convention that was never actually built. That section is being corrected in this same change to describe the declarative-proxy-plus-circuit-breaker reality.

Scope is `registry` and `ingestion` only — the only two routes with a real backing service today. `topology`, `contract`, and `intelligence` are empty placeholder modules (no code, no port, commented out of CI); adding routes or breakers for services that don't exist yet would be untestable. Each future route gets its breaker at the same time the route itself is created, following the pattern established here: breaker instance name identical to the route id, 5xx responses treated as failures alongside connection exceptions/timeouts, fallback throwing `ServiceUnavailableException` mapped to the envelope at HTTP 503.

An open breaker is visible under `/actuator/health` as a detail but does not flip the aggregate status — the gateway itself is healthy and correctly protecting itself from a struggling downstream; failing k8s readiness in that situation would pull the gateway out of the load balancer and make the incident worse, not better.

## Consequences

A future reader relying on `backend.md`'s old RestClient-based inter-service rule will find it no longer matches the gateway's architecture. Anyone adding a topology/contract/intelligence route later should copy this pattern (route + same-named breaker + fallback) rather than reintroducing a Java client layer, unless a later ADR explicitly revisits that choice (e.g. if gRPC research in Phase 6 changes the calling convention).
