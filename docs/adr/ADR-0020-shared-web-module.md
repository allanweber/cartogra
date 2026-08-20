# ADR-0020 — `shared:web` Gradle module for cross-service web filters

**Status**: Proposed
**Date**: 2026-05-20
**Deciders**: Allan Weber

---

## Context

Phase 5 task 5.Y requires every proxied backend service (registry, topology, contract, intelligence) to reject requests that do not carry a valid gateway-signed `X-Gateway-Token` header. The original task wording said the filter should live in `shared:common` "to avoid repeating the validation logic in every service."

CLAUDE.md is explicit:

> `shared:common` = plain Java only — ZERO Spring dependencies.

A token-validation filter must implement either `OncePerRequestFilter` (servlet stack — current gateway and backend services per ADR-0012) or `WebFilter` (reactive stack). Either way, it requires Spring Web. It cannot live in `shared:common` without breaking that rule and dragging Spring into every consumer of `shared:common`.

The audit identified this as **dead-end 10** in the phase 1–5 review.

---

## Decision

**Introduce a new Gradle module `shared:web`** that depends on `spring-web` (servlet) and optionally `spring-webflux` (reactive — only added if/when a reactive backend service exists). The token-validation filter and any future cross-service web filters live there.

Module layout:

```
shared/
├── common/         # plain Java; zero Spring deps (unchanged)
├── test-support/   # Testcontainers helpers (unchanged)
└── web/            # NEW — Spring Web utilities shared across services
    └── src/main/java/io/cartogra/web/
        ├── filter/GatewayTokenFilter.java
        └── filter/GatewayTokenProperties.java
```

`shared:web` depends on `shared:common` (envelope types, error model) but NOT vice versa.

Update CLAUDE.md so the "File Structure" section reflects three shared modules:

```
shared/
├── common/      # Plain Java only — ZERO Spring dependencies
├── test-support/ # Testcontainers helpers
└── web/         # Spring Web filters shared across backend services
```

Update the "Tech Stack" matrix so the row about cross-cutting filters notes `shared:web` as the home for them.

The filter contract:

| Aspect | Value |
|--------|-------|
| Header | `X-Gateway-Token` |
| Algorithm | HS256 |
| TTL | 30 s |
| Secret | Separate from user JWT secret; provided via `cartogra.gateway-token.secret` env var on each backend service |
| Claims | `iss = gateway`, `aud = <service-name>`, `iat`, `exp` |
| Failure mode | Reject with HTTP 401 and the standard error envelope; never proxy through |

The gateway, on every outbound `RestClient` call, signs and attaches the token in addition to forwarding the user JWT.

---

## Consequences

### Positive

- The `shared:common` zero-Spring-deps invariant is preserved.
- The token filter has one canonical implementation; future cross-service web concerns (e.g. a tenant-id-required filter, a content-type guard) have a clear home.
- Each backend service adds `implementation(project(":shared:web"))` to its `build.gradle.kts` and a single `@Bean` declaration; no per-service filter implementation.

### Negative / Trade-offs

- One new Gradle module. Existing CI builds will pick it up automatically; no new pipeline steps.
- A future reactive backend service (none planned for Phases 1–5) would need a sibling reactive filter. The module structure accommodates this without refactor.

### Neutral

- ADR-0010 (gateway is the sole JWT issuer) is unchanged; this ADR specifies *how* downstream services verify that the request came through the gateway.

---

## Alternatives Considered

| Option | Reason rejected |
|--------|-----------------|
| Put the filter in `shared:common` and accept Spring as a transitive dep | Violates CLAUDE.md; drags Spring Web into every consumer of `shared:common`. |
| Implement the filter independently in each of the four backend services | Four near-identical filters; cross-cutting bug-fixes require four PRs. |
| Make it a Spring Boot starter (`spring-boot-starter-cartogra-web`) | Premature; a starter wraps autoconfig + dependencies. A plain library module is enough until the platform has multiple optional cross-cutting filters. |

---

## References

- ADR-0010 — Gateway as the sole JWT issuer
- ADR-0012 — Gateway on Spring MVC servlet stack
- CLAUDE.md — `shared:common = plain Java only` rule
- Task 5.W (this ADR), 5.Y (the implementation it unblocks)
