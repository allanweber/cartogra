Status: ready-for-agent

# 1.62 — Auth route guards + sign out

## Problem Statement

Authenticated routes in the Cartogra frontend are already protected by the `_authenticated` layout guard, and a sign-out button exists in the AppLayout sidebar. However, three correctness gaps remain:

1. When a user initiates an OAuth flow from a redirected login page (e.g., bounced from `/catalog/abc`), the intended destination is silently dropped — after OAuth completes, the user always lands on `/dashboard` instead of where they were going.
2. After a successful sign-out, the TanStack Query session cache is not cleared, meaning a second user signing in on the same tab could briefly see stale session data until the 5-minute stale time expires.
3. There is no Vitest configuration in the frontend, so the `0.35` task that marked it complete has never been verified; no behavioral tests exist for any auth store or sign-out logic.

## Solution

Fix the OAuth redirect round-trip using `sessionStorage` as a tab-scoped relay (no gateway changes needed). Fix the query cache leak in the sign-out handler. Establish a working Vitest configuration and add tests covering the auth store, sign-out flow, and OAuth sessionStorage handoff.

The route guard mechanism itself (`_authenticated.tsx` `beforeLoad`) is already correct and complete — it is the idiomatic TanStack Router pattern for protecting route groups, and no changes to it are required.

## User Stories

1. As an authenticated user, I want clicking "Sign out" to fully clear my session (cookie, query cache, and auth store), so that a subsequent user on the same machine cannot see my data.
2. As an authenticated user, I want to be redirected back to the page I was trying to reach after signing in with OAuth, so that I do not lose my place when my session expires.
3. As an authenticated user, I want to be redirected back to the page I was trying to reach after signing in with email and password, so that I do not have to navigate back manually.
4. As an unauthenticated user navigating directly to a protected route, I want to be redirected to the login page with my intended destination preserved, so that after I authenticate I land where I wanted to go.
5. As an authenticated user, I want the sign-out flow to complete and redirect me to `/login` even if the backend logout call fails, so that a network error does not trap me in a half-signed-out state.
6. As a developer, I want a working Vitest configuration, so that I can write and run unit tests for frontend auth logic.
7. As a developer, I want the auth store behavior to be covered by tests, so that regressions in `clearAuth` or `hydrateWith` are caught before they reach production.
8. As a developer, I want the sign-out handler to be tested including the error path, so that the "proceed even if the server call fails" contract is enforced.
9. As a developer, I want the OAuth sessionStorage relay to be tested, so that the write-before-start and read-clear-on-success contract is explicit and guarded.

## Implementation Decisions

### Route guard architecture

The route guard lives in the `_authenticated` layout route's `beforeLoad` hook — the idiomatic TanStack Router pattern. Protected routes are opted in by placing them under the `_authenticated/` route group. There is no exclusion list on the root route. The guard already handles SSR (fetches session from the middleware) and browser hydration (checks the auth store, falls back to TanStack Query `ensureQueryData`). No changes to this mechanism are required.

Public routes — `/login`, `/register`, `/verify-email`, `/forgot-password`, `/reset-password`, `/oauth-handoff` — remain outside the `_authenticated/` group and require no guard configuration.

### OAuth redirect round-trip via sessionStorage

The OAuth start navigation (`window.location.href = .../oauth/{provider}/start`) is a full-page redirect that exits the SPA. The gateway `state` parameter stores only tenant identity, not a UI destination. Threading the redirect through the gateway would require backend changes.

Instead, before initiating the OAuth redirect, the frontend writes the intended destination to `sessionStorage` under the key `pendingRedirect`. The `oauth-handoff` route reads and immediately deletes this key on a successful callback, then navigates to the stored value or falls back to `/dashboard`. This approach:

- Requires no gateway or Redis changes
- Survives the full-page OAuth round-trip in the same browser tab
- Fails gracefully: if `pendingRedirect` is absent or the tab was opened fresh, the fallback is `/dashboard`

The `handleOAuth` function in the login page writes `pendingRedirect` only when a non-null `redirectTo` search param is present.

### Sign-out query cache clearing

The sign-out handler in `UserMenu` must call `queryClient.removeQueries({ queryKey: ['session'] })` before clearing the auth store and navigating. This matches the pattern already used at login and at the OAuth handoff. `UserMenu` gains a `useQueryClient()` call to obtain the client instance.

### Vitest configuration

- `vitest.config.ts` at the frontend root with `environment: 'happy-dom'` and a `setupFiles` entry pointing to `src/test/setup.ts`
- `src/test/setup.ts` imports `@testing-library/jest-dom/vitest` to extend Vitest matchers
- `package.json` gains a `"test": "vitest"` script alongside the existing `"typecheck"` script

### PlantUML diagrams

Two sequence diagrams are required per the workflow rules:
- `docs/diagrams/frontend/sign-out-sequence.puml` — the sign-out flow from user click through API call, cache/store/cookie clearing, to `/login` navigation, including the error branch
- `docs/diagrams/frontend/oauth-redirect-sequence.puml` — the full OAuth redirect round-trip: login page writes `sessionStorage`, browser navigates to provider, gateway issues callback, `oauth-handoff` reads `sessionStorage` and navigates to destination

## Testing Decisions

Good tests for this feature verify observable behavior — state transitions in the auth store, side effects of the sign-out handler, and the sessionStorage contract — without asserting implementation internals like which internal methods were called in which order.

The `_authenticated` layout's `beforeLoad` is not unit-tested here. It is two conditional branches and a `redirect` throw; the real verification is a manual browser smoke test. Router integration tests requiring the full TanStack Router harness are deferred to the Playwright suite in task 5.14.

**Modules under test:**

- **`useAuthStore`** — pure Zustand store, no mocking required. Assert that `clearAuth()` sets `user` to `null` and `isAuthenticated` to `false`. Assert that `hydrateWith(user)` sets `user`, `isAuthenticated: true`, and `isHydrated: true`.

- **Sign-out handler (`handleLogout`)** — mock `apiMutate`, `clearSessionCookie`, `queryClient.removeQueries`, and `navigate`. Assert the happy path calls all four in order. Assert the error path (where `apiMutate` throws) still calls `removeQueries`, `clearAuth`, and `navigate` — the "proceed even if the server call fails" contract.

- **OAuth sessionStorage relay** — mock `sessionStorage` and `window.location`. Assert that `handleOAuth` writes `pendingRedirect` to `sessionStorage` when `redirectTo` is set, and does not write when `redirectTo` is absent. Assert that the `oauth-handoff` success branch reads and deletes `pendingRedirect`, and navigates to its value. Assert the fallback to `/dashboard` when the key is absent.

**Prior art:** The existing `LoginUseCaseTest` and `RegisterUserUseCaseTest` in the gateway service demonstrate the pattern of mocking dependencies and asserting on outcomes — the same philosophy applies here in a Vitest context.

## Out of Scope

- Gateway changes of any kind — the `OAuthStartUseCaseImpl` and `OAuthController` are untouched
- Playwright / E2E tests — full router integration coverage is deferred to task 5.14
- Router integration tests for `_authenticated.tsx` `beforeLoad` — the guard logic is already in place and correct
- Persistent redirect storage (e.g., localStorage) — sessionStorage is sufficient for the same-tab OAuth round-trip
- Per-tenant rate limiting on the logout endpoint — covered separately in task 1.68
- Any changes to the `/login`, `/register`, `/verify-email`, `/forgot-password`, or `/reset-password` pages beyond the `handleOAuth` sessionStorage write

## Further Notes

The `_authenticated.tsx` `beforeLoad` guard and the `AppLayout` sign-out button both existed before this task. The net-new code in 1.62 is: Vitest configuration, the `pendingRedirect` sessionStorage relay in two files, the `queryClient.removeQueries` call in `handleLogout`, and the associated tests and diagrams.

The `RootNotFound` component in `__root.tsx` redirects unauthenticated 404s to `/login` without a `redirect` search param — this is intentional. There is no value in redirecting back to a URL that does not exist.
