# ADR-0011 — httpOnly Cookie + Bearer Dual-Mode Auth

**Status:** Accepted  
**Date:** 2026-05-12  
**Deciders:** Allan Weber

---

## Context

The platform must serve two types of clients: browser-based single-page applications and non-browser API clients (CLI tools, CI pipelines, automation scripts). These clients have different capabilities for handling tokens securely.

---

## Decision

Browser clients receive JWTs via `HttpOnly; Secure; SameSite=Lax` cookies. Non-browser clients use `Authorization: Bearer <token>`. Neither path ever uses `localStorage` or exposes tokens to JavaScript on the page.

---

## Rationale

- `localStorage` tokens are readable by any JavaScript on the page — XSS becomes instant account takeover with no mitigation possible.
- `HttpOnly` cookies are inaccessible to scripts, eliminating the XSS-to-token-theft vector.
- `SameSite=Lax` provides CSRF protection for state-mutating requests (POST, PUT, DELETE) without requiring custom CSRF tokens or double-submit patterns.
- `Secure` ensures cookies are only transmitted over HTTPS, preventing interception on unencrypted connections.
- The `Bearer` path is essential for CLI tools, CI automation, and API clients that cannot handle cookies.
- Refresh tokens use the same dual-mode approach but are scoped to `Path=/v1/auth/refresh` to limit the surface area where they are sent automatically.

---

## Consequences

- `JwtAuthenticationWebFilter` must try both extraction paths in a defined order: cookie first (preferred for browsers), then `Authorization: Bearer` header (for API clients). Both paths authenticate identically — there is no capability difference.
- The `/v1/auth/login` response must both set the `jwt` cookie AND return the access token in the response body, so non-browser callers can extract the token directly without parsing `Set-Cookie` headers.
- The same token is used for both paths — there is no separate "browser token" vs "API token". The issuance and validation logic is unified in `JwtTokenProvider`.
- Cookie `maxAge` is set to `accessTokenExpirySeconds` (default: 900s). When the cookie expires, the browser automatically discards it, preventing stale-token confusion.

---

## Alternatives Considered

**localStorage + bearer only** — rejected. XSS is a realistic threat; cookie-based auth is significantly more resilient.  
**Session cookies (server-side sessions)** — rejected. Stateful sessions conflict with the horizontal scaling model and add Redis session storage complexity beyond what refresh tokens already provide.  
**PKCE + SPA OAuth flow** — rejected for MVP. Adds complexity (authorization server, client registration) without MVP-scope benefit. The gateway-as-issuer model is simpler and sufficient.
