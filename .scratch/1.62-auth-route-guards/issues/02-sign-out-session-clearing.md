Status: done

## Parent

`.scratch/1.62-auth-route-guards/PRD.md` — Task 1.62: Auth route guards + sign out

## What to build

Fix the sign-out handler in `UserMenu` so it fully clears the session: the TanStack Query cache, the auth store, and the session cookie. Then cover that behavior with unit tests.

**Code change:** `UserMenu` in `AppLayout` currently calls `apiMutate`, `clearSessionCookie`, `clearAuth`, and `navigate` — but it does not clear the TanStack Query session cache. Add `useQueryClient()` to the component and call `queryClient.removeQueries({ queryKey: ['session'] })` in `handleLogout` before `clearAuth()`. This matches the pattern already used at login.

**Tests (new file `src/test/auth-store.test.ts`):**
- `useAuthStore.clearAuth()` sets `user` to `null` and `isAuthenticated` to `false`
- `useAuthStore.hydrateWith(user)` sets `user`, `isAuthenticated: true`, and `isHydrated: true`

**Tests (new file `src/test/handle-logout.test.ts`):**
- Happy path: when `apiMutate` resolves, all four side effects fire — `removeQueries`, `clearSessionCookie`, `clearAuth`, `navigate({ to: '/login' })`
- Error path: when `apiMutate` throws, `removeQueries`, `clearSessionCookie`, `clearAuth`, and `navigate` still fire — the "proceed even if the server call fails" contract

## Acceptance criteria

- [ ] `UserMenu` calls `queryClient.removeQueries({ queryKey: ['session'] })` in `handleLogout` before `clearAuth()`
- [ ] `UserMenu` obtains the query client via `useQueryClient()` (constructor injection equivalent — no global import)
- [ ] `src/test/auth-store.test.ts` passes: `clearAuth` resets to `null`/`false`; `hydrateWith` sets user, `isAuthenticated: true`, `isHydrated: true`
- [ ] `src/test/handle-logout.test.ts` passes: happy path fires all four side effects; error path still fires clear + navigate
- [ ] `pnpm test --run` is green

## Blocked by

`.scratch/1.62-auth-route-guards/issues/01-vitest-infrastructure.md`
