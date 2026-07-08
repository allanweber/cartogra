# Identity & Access Context — Gateway

**Service**: `services/gateway` · Port `8080` · Phase 0/1 · **Live**

---

## Purpose

The Gateway is Cartogra's single entry point and the sole issuer of identity tokens. It authenticates every request, establishes tenant context, enforces rate limits, and reverse-proxies to downstream domain services. No domain logic lives here — only access control and routing.

---

## Ubiquitous Language

| Term | Meaning in this context |
|---|---|
| **Tenant** | An organisation (company / team) that has signed up; the top-level isolation boundary |
| **User** | A human identity belonging to exactly one Tenant |
| **JWT** | Short-lived (15 min) signed token the Gateway issues; carries `sub`, `tid`, `email`, `roles[]` |
| **Refresh Token** | Long-lived (30 day) opaque token stored httpOnly; exchanged for a new JWT |
| **X-Tenant-Id** | HTTP header the Gateway injects from the validated JWT before proxying; clients never supply it |
| **Rate limit** | Redis token-bucket, scoped per Tenant when a request carries a valid JWT, else per IP; auth routes: 5 req/s burst 10; default: 20 req/s burst 40 (same numeric limits for both scopes — no per-tenant override yet) |
| **OTP** | One-time 6-digit email code used to verify a new account (sent via Resend) |
| **OIDC** | Per-tenant SSO config (discovery URI + client credentials) |
| **API Key** | Tenant-scoped `X-Cartogra-Api-Key` for CI/automation; never HMAC |
| **Circuit Breaker** | Resilience4j gate on a proxied route; after repeated failures (5xx or exception/timeout) it opens and short-circuits to a fallback instead of calling the downstream. One breaker instance per downstream, named identically to its Spring Cloud Gateway route id (`registry`, `ingestion`) |
| **Downstream Service Name** | The single identifier shared across a route id, its circuit breaker instance name, its fallback path segment, and the value carried on `ServiceUnavailableException` — never diverges per use site |
| **ServiceUnavailableException** | Domain exception thrown by the fallback handler when a breaker is open; carries the Downstream Service Name; mapped by `GlobalExceptionHandler` to HTTP 503 + `ErrorCodes.SERVICE_UNAVAILABLE` |

---

## Core Responsibilities

1. **Token issuance** — local email/password + OTP, Google OAuth, GitHub OAuth, per-tenant OIDC
2. **Token validation** — JWT filter on every authenticated route
3. **Tenant injection** — strips any client-supplied `X-Tenant-Id`, replaces from JWT `tid` claim
4. **Rate limiting** — Redis Lua token-bucket; returns `429` with `Retry-After` and error envelope
5. **Reverse proxy** — Spring Cloud Gateway routes `/api/v1/**` → Registry (and future services); each route carries a Circuit Breaker that opens on repeated downstream failure and returns an envelope-shaped `SERVICE_UNAVAILABLE` error instead of a raw proxy error
6. **Tracing** — adds `X-Trace-Id` response header; propagates W3C `traceparent` to all proxied requests

---

## Domain Model

```
Tenant ──< User
User ──< RefreshToken
Tenant ──○ TenantOidcConfig   (one optional SSO config per tenant)
```

**Key entities** (all records, Spring Data JDBC):

| Entity | Table | Notes |
|---|---|---|
| `Tenant` | `tenants` | slug UNIQUE; plan: free/pro/enterprise |
| `User` | `users` | email UNIQUE; password_hash NULL for OAuth-only; roles TEXT[] |
| `RefreshToken` | `refresh_tokens` | token_hash (SHA-256, raw never stored); revoked_at soft-revoke |
| `TenantOidcConfig` | `tenant_oidc_configs` | discovery_uri + client_id/secret; one per tenant |

**No Flyway migrations** — reads the Registry service's database tables directly (Phase 0 compromise, ADR-0010).

---

## Inbound Ports (API)

All responses use the standard `ApiResponse<T>` / `ApiErrorResponse` envelope except OAuth callbacks (redirects).

| Method | Path | Description |
|---|---|---|
| POST | `/api/auth/register` | Register new user; sends OTP email |
| POST | `/api/auth/verify` | Verify email OTP; activates account |
| POST | `/api/auth/resend-verification` | Resend OTP |
| POST | `/api/auth/login` | Local login; sets httpOnly JWT + refresh cookies |
| POST | `/api/auth/refresh` | Exchange refresh token for new JWT |
| POST | `/api/auth/logout` | Revoke refresh token; clear cookies |
| POST | `/api/auth/forgot-password` | Send password reset email |
| POST | `/api/auth/reset-password` | Apply reset token |
| GET | `/api/auth/userinfo` | Return current user info (requires JWT) |
| GET/POST | `/api/auth/oauth/{provider}/start` | Initiate OAuth (google/github) |
| GET | `/api/auth/oauth/{provider}/callback` | OAuth callback |
| POST | `/api/auth/admin/oidc` | Configure per-tenant OIDC (ADMIN role) |
| `/**` | `/api/v1/**` → Registry | Reverse-proxy all domain API traffic |

---

## Outbound Ports (Dependencies)

| Dependency | Type | Purpose |
|---|---|---|
| PostgreSQL (registry DB) | Spring Data JDBC | Read users, tenants, refresh_tokens, tenant_oidc_configs |
| Redis (Valkey) | Lua script via `RedisTemplate` | Rate-limit token buckets; keyed `rate_limit:tenant:{tenantId}:{auth\|default}` (JWT present) or `rate_limit:ip:{ip}:{auth\|default}` (no JWT) |
| Resend API | REST (HTTP) | Send OTP and password-reset emails |
| Google / GitHub OAuth | REST (HTTP) | OAuth2 authorization code exchange |
| Registry service | Spring Cloud Gateway proxy | Forward `/api/v1/**` |

---

## Filter Chain (order)

1. `JwtAuthenticationFilter` — validates JWT; populates `SecurityContext`
2. `RateLimitFilter` — Redis token-bucket; short-circuits 429 on exhaustion
3. `TenantInjectionFilter` — strips/injects `X-Tenant-Id` and `X-User-Id`
4. `ProxyRequestLoggingFilter` — structured log per proxied request

---

## Kafka

None. The Gateway neither produces nor consumes Kafka events — it is a synchronous boundary.

---

## Context Relationships

| Neighbour | Relationship | Notes |
|---|---|---|
| Service Catalog (Registry) | Open Host Service → Conformist | Gateway proxies all `/api/v1/**` to Registry; injects validated tenant context |
| Topology, Contract, Intelligence | Open Host Service → Conformist | Same proxy pattern (Phase 2/3/4) |
| Shared Kernel (`shared:common`) | Shared Kernel | Uses `ApiResponse`, `ApiError`, `ApiErrorResponse`, `ErrorCodes` |

---

## Key Files

| Path | Role |
|---|---|
| `src/main/java/.../config/SecurityConfig.java` | Spring Security filter chain + RBAC |
| `src/main/java/.../config/JwtConfig.java` | HS256 key config, token TTL |
| `src/main/java/.../config/RateLimitProperties.java` | Rate limit config props |
| `src/main/java/.../config/OAuthConfig.java` | Google + GitHub OAuth properties |
| `src/main/resources/application.yml` | All runtime config (JWT secret env var, OAuth keys, Resend key, Redis URL) |
| `src/test/resources/db/test-schema.sql` | In-memory schema for integration tests |

---

## ADRs

- ADR-0010 — Gateway is the sole JWT issuer (no separate auth microservice)
- ADR-0011 — httpOnly cookie + Bearer dual-auth strategy
- ADR-0012 — Servlet stack (Spring MVC) chosen over reactive (Spring WebFlux)
- ADR-0013 — Guest demo access deferred to Phase 5
- ADR-0019 — Guest enforcement via gateway-issued guest JWT (Phase 5)
