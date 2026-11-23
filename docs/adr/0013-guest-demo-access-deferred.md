# ADR-0013 — Guest Demo Access Deferred to Phase 5

**Status**: Accepted
**Date**: 2026-05-20
**Deciders**: Allan Weber

---

## Context

Phase 1 delivers the Gateway MVP with JWT-based authentication (email/password, Google OAuth, GitHub OAuth), multi-tenant isolation, and role-based access control (VIEWER, MEMBER, ADMIN). The original product backlog included an optional guest/anonymous demo mode that would allow unauthenticated visitors to browse a read-only view of a curated demo tenant.

Before implementing any code for guest access, a scope decision was required:

- Should guest access be delivered in Phase 1 alongside the auth MVP?
- If deferred, what is the risk to the Phase 1 milestone?

The key tradeoffs considered:

1. **Scope risk**: Adding a fourth auth path (anonymous / guest JWT) to the Gateway in Phase 1 increases the surface area of the auth subsystem during the most critical stabilisation period.
2. **Rate-limit bypass risk**: Any unauthenticated path that reaches proxied services must still respect rate limits. Getting this wrong in Phase 1 could create an unprotected endpoint.
3. **Tenant isolation risk**: A demo tenant read-only view requires either a dedicated demo tenant with guaranteed data or server-side filtering — neither is designed or seeded in Phase 1.
4. **Value timing**: Guest access has no external users in Phase 1; it becomes relevant only when the product is publicly demo-able (Phase 5 — Public Launch).

---

## Decision

**Guest demo access is NOT enabled in Phase 1.**

All requests to `/api/v1/**` continue to require a valid JWT with at least VIEWER role, as enforced by the Gateway `SecurityConfig`. No GUEST role, no anonymous auth path, and no unauthenticated proxy route are introduced.

The feature is deferred to **Phase 5 — Public Launch**, where it will be designed with:
- A dedicated `GUEST` role scoped to a single read-only demo tenant
- A short-lived, auto-issued guest token (no credentials required)
- Rate limits applied to guest tokens at least as strictly as to authenticated users
- Server-side filtering to prevent cross-tenant data leakage

---

## Consequences

**Positive**:
- Phase 1 auth surface remains minimal and auditable.
- No risk of an unauthenticated route reaching proxied services before rate-limit and tenant-injection filters are production-hardened.
- No seeded demo data required for Phase 1 acceptance.

**Negative**:
- Early external demos require a real account (VIEWER role is sufficient).
- The guest feature must be designed and tested in Phase 5 under more time pressure.

**Neutral**:
- No code change required — the existing `SecurityConfig` already enforces the correct behaviour. This ADR serves as an explicit record that the absence of guest access is intentional, not an oversight.
