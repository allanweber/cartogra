Status: done

## Parent

`.scratch/1.62-auth-route-guards/PRD.md` — Task 1.62: Auth route guards + sign out

## What to build

When a user is bounced to `/login?redirect=/catalog/abc` and then clicks a social OAuth button, the intended destination is silently dropped — after the OAuth round-trip the user always lands on `/dashboard`. Fix this using `sessionStorage` as a tab-scoped relay.

**Write side (`login.tsx`):** In `handleOAuth`, before setting `window.location.href`, check if `redirectTo` (the `?redirect` search param) is non-null. If so, write `sessionStorage.setItem('pendingRedirect', redirectTo)`. Do not write when `redirectTo` is absent.

**Read side (`oauth-handoff.tsx`):** In the `apiFetch` success `.then()` callback, after the existing `queryClient.removeQueries` call, read `sessionStorage.getItem('pendingRedirect')`, immediately call `sessionStorage.removeItem('pendingRedirect')` to clear it, then `navigate({ to: destination ?? '/dashboard' })` instead of the current hardcoded `/dashboard`.

No gateway changes. The Gateway's `state` parameter stores only tenant identity — the redirect destination is a pure frontend concern.

**Tests (new file `src/test/oauth-redirect-relay.test.ts`):**
- `handleOAuth` writes `pendingRedirect` to `sessionStorage` when `redirectTo` is set
- `handleOAuth` does NOT write `pendingRedirect` when `redirectTo` is absent
- `oauth-handoff` success branch reads `pendingRedirect`, removes it from `sessionStorage`, and navigates to the stored value
- `oauth-handoff` success branch navigates to `/dashboard` when `pendingRedirect` is absent

## Acceptance criteria

- [ ] `handleOAuth` in `login.tsx` calls `sessionStorage.setItem('pendingRedirect', redirectTo)` only when `redirectTo` is non-null, immediately before the `window.location.href` assignment
- [ ] `oauth-handoff.tsx` success branch reads `pendingRedirect`, deletes it, navigates to its value or `/dashboard`
- [ ] `src/test/oauth-redirect-relay.test.ts` passes: write fires only when `redirectTo` is set; read-clear-navigate on success; fallback to `/dashboard` when key is absent
- [ ] `pnpm test --run` is green
- [ ] The hardcoded `navigate({ to: '/dashboard' })` in `oauth-handoff.tsx` is replaced

## Blocked by

`.scratch/1.62-auth-route-guards/issues/01-vitest-infrastructure.md`
