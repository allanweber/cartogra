# ADR-0010 — Gateway as Sole Token Issuer

**Status:** Accepted  
**Date:** 2026-05-12  
**Deciders:** Allan Weber

---

## Context

The platform needs a JWT issuance point for multi-tenant authentication. The two natural candidates are: (1) the API gateway service, or (2) a dedicated auth microservice.

---

## Decision

The `gateway` service is the **only** component that issues JWTs. No separate auth microservice exists for MVP scope.

---

## Rationale

- The gateway already sits at the perimeter — it is the natural place to validate credentials and issue tokens.
- An MVP auth microservice adds one extra hop, one extra service to operate, and one more gRPC contract to maintain without adding capability that a single-service implementation cannot provide.
- Tenant context injection (task 1.23) and rate limiting (task 1.25) are cleanest when auth and routing share the same process — both filters read the same `ReactiveSecurityContextHolder` and Redis connection pool.
- Splitting auth into a separate service is a Phase 5+ concern, when the auth surface grows beyond a single gateway (e.g., dedicated identity federation, SCIM provisioning, device flow).

---

## Consequences

- Gateway must hold a Spring Data JDBC connection to the shared PostgreSQL database (same DB as registry, `?currentSchema=registry`).
- Gateway is no longer purely stateless — it stores refresh token hashes and OIDC config in PostgreSQL and OAuth state in Redis.
- A future extraction into a dedicated auth service requires migrating JWT issuance, refresh token tables, and OIDC config out of the gateway. The use-case interfaces (`RegisterUserUseCase`, `LoginUseCase`, etc.) are already isolated behind clean boundaries, making this migration tractable.

---

## Alternatives Considered

**Separate auth microservice** — rejected for MVP. Adds deployment complexity and a synchronous gRPC call on every protected request with no MVP-scope benefit. Revisit in Phase 5.
