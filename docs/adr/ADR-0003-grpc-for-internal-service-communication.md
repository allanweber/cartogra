# ADR-0003 — gRPC for internal service-to-service communication

**Date:** 2026-05-01
**Status:** Accepted
**Deciders:** Platform team

---

## Context

Cartogra services need to make direct synchronous calls to each other. Examples:

- `topology` → `registry`: resolve service metadata before computing blast radius
- `intelligence` → `registry`: fetch service list for health-score batch
- `contract` → `registry`: look up declared consumers before running a compatibility check
- `ingestion` → `registry`: upsert discovered service records

The project previously implied REST for these calls, but no pattern, HTTP client skeleton, or tracing contract was ever documented. Each engineer would invent their own approach — inconsistent tracing propagation, no compile-time contract enforcement, no streaming support, and verbose JSON overhead on every internal call.

Additional driver: some internal calls benefit from **server-side streaming**. The topology service building a dependency graph traversal can push nodes progressively as they are resolved rather than buffering the entire result set in memory.

The project already has:
- A clearly defined external surface (REST/OpenAPI at the Gateway, browser-safe, JSON, documented)
- A clearly defined async channel (Kafka, event envelope, W3C `traceparent` headers)
- No existing internal sync pattern at all — this ADR establishes it from scratch

---

## Decision

Adopt **gRPC** for all direct synchronous service-to-service calls.

- **External surface (client → Gateway)**: remains REST/JSON (OpenAPI-documented, browser-compatible)
- **Internal sync (service → service)**: gRPC over HTTP/2 with protobuf encoding
- **Internal async (service → service)**: Kafka (unchanged)

### Implementation choices

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Framework integration | `org.springframework.grpc:spring-grpc-spring-boot-starter:1.0.1` | Official Spring project; Spring Boot 4.x native support; auto-configures server and channel |
| Transport | `io.grpc:grpc-netty-shaded:1.80.0` | Shaded Netty avoids version conflicts with Spring's own Netty |
| Protobuf runtime | `com.google.protobuf:protobuf-java:4.33.0` | Stable protobuf 4 release |
| Code generation | `com.google.protobuf:protobuf-gradle-plugin:0.10.0` + `io.grpc:protoc-gen-grpc-java:1.80.0` | Standard toolchain |
| Proto ownership | New `shared:contracts` Gradle module | Single source of truth; generated stubs published as a library; services depend on `:shared:contracts` |
| OTel propagation | Spring Boot OTel starter auto-instruments gRPC channels | No manual interceptor needed when `spring-boot-starter-opentelemetry` is on the classpath |
| Tenant propagation | gRPC `Metadata` key `x-tenant-id` on every call | Mirrors the HTTP header convention; extracted by a server interceptor into a `ScopedValue` |
| Streaming | Server-side streaming allowed (`stream` return type in `.proto`) | Sufficient for topology traversal and watch endpoints |

---

## Consequences

### Positive

- **Compile-time contracts**: protoc catches breaking changes at build time; `.proto` diffs are reviewable and versioned.
- **Performance**: binary encoding over HTTP/2 multiplexing; significantly smaller payloads than JSON on high-frequency internal calls.
- **Streaming**: server-side streaming unlocks progressive delivery for graph traversal, watch APIs, and bulk exports.
- **OTel auto-instrumented**: Spring Boot's OTel starter auto-instruments gRPC server and client channels — trace context propagates automatically.
- **Tenant isolation enforced at the transport layer**: a single gRPC server interceptor extracts and validates `x-tenant-id` metadata on every incoming call.
- **Single source of truth for contracts**: `shared:contracts` makes all inter-service APIs visible and auditable in one place.

### Negative / Trade-offs

- **New tooling**: protoc + `protobuf-gradle-plugin` added to the build. Generated code lives in `build/generated/` and must not be committed.
- **Harder to debug with curl**: requires `grpcurl` or a gRPC-capable client (Postman, Evans) for ad-hoc testing.
- **Browser clients cannot call gRPC directly** — acceptable because the Gateway is the only external surface; browsers always talk to the Gateway's REST API.
- **Proto evolution discipline required**: field numbers must never be reused; deprecated fields use `reserved`; breaking changes require a new version suffix (e.g., `registry/v2/`).
- **Spring gRPC 1.x targets Spring Boot 4.x** — upgrading the Spring Boot version in the future requires verifying spring-grpc compatibility.

### Neutral

- Services that only consume Kafka and never receive direct calls from other services do not need the gRPC server starter.
- Services that only expose gRPC and receive no external HTTP traffic still expose Actuator health probes on the standard management port (separate from the gRPC port).

---

## Alternatives Considered

| Option | Reason rejected |
|--------|----------------|
| Keep REST internally (Spring's `RestClient`) | No compile-time contracts; JSON overhead; no native streaming; no single source of truth for internal APIs |
| GraphQL federation | Internal calls are not UI-driven queries; overhead of schema stitching not justified; no streaming advantage |
| All-async via Kafka | Some calls require immediate synchronous response (e.g., `registry` lookup before publishing an event); adding a reply-topic pattern adds latency and complexity with no benefit |
| gRPC-web at Gateway | Only needed if browsers must call gRPC directly — they don't; Gateway translates REST ↔ internal gRPC if needed |

---

## References

- [grpc-java v1.80.0](https://github.com/grpc/grpc-java/releases/tag/v1.80.0)
- [spring-projects/spring-grpc v1.0.1](https://github.com/spring-projects/spring-grpc/releases/tag/v1.0.1)
- [protobuf-gradle-plugin v0.10.0](https://github.com/google/protobuf-gradle-plugin/releases/tag/v0.10.0)
- [ADR-0001](ADR-0001-postgresql-over-graph-database.md) — persistence decision
- [system-overview.md](../architecture/system-overview.md) — updated communication model
