# ADR-0019 — Guest enforcement mechanism

**Status**: Proposed
**Date**: 2026-05-20
**Deciders**: Allan Weber

---

## Context

ADR-0013 defers guest demo access to Phase 5. Phase 5 task 5.4 says "Enforce guest read-only mode for the Acme tenant and sandbox isolation for authenticated demo users" but does not specify the enforcement mechanism. Three viable shapes:

| Mechanism | Description | Cost |
|-----------|-------------|------|
| **(a) Special JWT role** | Gateway issues a short-lived JWT with `roles=["guest"]` and `tid=<acme-tenant-id>`. All existing `@PreAuthorize` checks on mutating endpoints require `member` or `admin`, so guest tokens are read-only by virtue of failing every write authorisation. | Zero new auth code path. Requires one audit pass to confirm every write across backend services is guarded. |
| **(b) Anonymous path with header injection** | Gateway accepts unauthenticated requests for a curated set of public read routes, injects synthetic `X-Tenant-Id` + `X-Roles: guest`. | New unauthenticated branch in gateway security config; one wrong route exposes write surface. |
| **(c) Per-route ACL** | A new `route_acls` table; gateway consults it per request. | New table + caching + admin UI. Heavy for a guest-mode-only feature. |

The audit identified this as **dead-end 12** in the phase 1–5 review.

---

## Decision

**Recommend option (a) — special JWT role.**

Implementation shape:

1. **Gateway** issues a guest JWT with payload `{ "sub": "guest-<random-uuid>", "tid": "<acme-tenant-id>", "roles": ["guest"], "exp": <now + 60 min> }`. The endpoint that mints it (`POST /auth/guest`) is rate-limited stricter than other `/auth/*` endpoints and requires no credentials.
2. **Backend services** (registry, topology, contract, intelligence) extend their existing `@EnableMethodSecurity` configuration so every mutating use case carries `@PreAuthorize("hasAnyRole('MEMBER','ADMIN')")`. Read-only endpoints either carry `hasAnyRole('VIEWER','MEMBER','ADMIN','GUEST')` or have no explicit role check (already covered by the JWT-required gateway filter).
3. **Acme tenant data isolation**: the guest JWT carries `tid=<acme-tenant-id>` and the existing tenant-injection filter (task 1.23) already strips client-supplied `X-Tenant-Id`. Tenant boundary enforcement is therefore reused; nothing new.
4. **Sandbox isolation** for authenticated demo users (the second half of 5.4) is a separate concern handled by a per-tenant sandbox flag on `tenants` table or a dedicated demo-tenant pool — outside the scope of this ADR.

Rationale:

1. **Reuses every existing auth path**: tenant injection, rate limiting, RBAC, audit logging all work without modification.
2. **Fail-closed**: a missing `@PreAuthorize` on a write endpoint is already a bug per CLAUDE.md; the guest mode just makes it externally exploitable, which sharpens the audit. The audit becomes a forcing function for tightening write authorisation across all backend services.
3. **No anonymous path**: option (b) creates a class of routes that bypass the gateway's JWT filter — that is exactly the surface area we want to keep zero per ADR-0013's stated Phase 1 risks.

The 5.4 acceptance criteria therefore become:

- `POST /auth/guest` mints a guest JWT (gateway).
- Every mutating endpoint in registry, topology, contract, intelligence carries `@PreAuthorize` requiring at least `MEMBER`.
- A guest token cannot write — proven by an integration test that mints a guest JWT and asserts 403 on every documented write path.
- A guest token can read Acme tenant data — proven by a happy-path Playwright test (task 5.20).

---

## Consequences

### Positive

- Zero new auth shape; all existing filters, rate limits, and audit hooks already apply.
- A guest who finds a missing-`@PreAuthorize` bug exposes a write surface that was already broken for every viewer-role user — guest mode just makes the bug louder.
- Sandbox isolation work can stay independent.

### Negative / Trade-offs

- Requires a code audit across all backend services confirming every write is guarded. This is real work, but it is work that should have been done anyway per CLAUDE.md's RBAC rule. The audit is folded into task 5.4's done-definition.
- Guest tokens are bearer tokens — if leaked, an attacker can read Acme tenant data until expiry. Acceptable: Acme demo tenant is by definition public-curated data.

### Neutral

- ADR-0013's deferral wording is preserved; this ADR specifies the implementation shape that ADR-0013 deliberately left open.

---

## Alternatives Considered

| Option | Reason rejected |
|--------|-----------------|
| (b) Anonymous gateway path | Creates an auth-bypass class of routes; one misconfiguration exposes writes. |
| (c) Per-route ACL table | Heavyweight for a single-tenant, single-mode feature. |
| Hardcoded "guest" Spring Security role with disabled-token semantics | Mixes config and code; harder to reason about than a normal JWT with a constrained role. |

---

## References

- ADR-0013 — Guest demo access deferred to Phase 5 (this ADR specifies the *how*)
- ADR-0010 — Gateway as the sole JWT issuer (guest JWTs follow the same rule)
- Task 5.3a (this ADR), 5.4 (the enforcement implementation)
