Status: done

## Parent

`.scratch/1.62-auth-route-guards/PRD.md` — Task 1.62: Auth route guards + sign out

## What to build

Create two PlantUML sequence diagrams documenting the auth flows introduced or corrected in 1.62. These are required by the project workflow rules (every feature with new code must have diagrams) and must reflect the actual implemented behavior.

**`docs/diagrams/frontend/sign-out-sequence.puml`** — the full sign-out flow:
- Actor clicks "Sign out" in AppLayout sidebar
- `handleLogout` calls `POST /api/auth/logout` (error branch: server call throws, flow continues)
- `queryClient.removeQueries({ queryKey: ['session'] })` clears TanStack Query session cache
- `clearSessionCookie()` clears the browser session cookie
- `useAuthStore.clearAuth()` resets the auth store
- `navigate({ to: '/login' })` redirects the user

**`docs/diagrams/frontend/oauth-redirect-sequence.puml`** — the OAuth redirect round-trip with the `sessionStorage` relay:
- User is bounced to `/login?redirect=/some/path` by the `_authenticated` layout guard
- User clicks OAuth button; `handleOAuth` writes `sessionStorage.pendingRedirect = '/some/path'`
- Browser navigates to OAuth provider start URL (full-page redirect exits SPA)
- Provider redirects to `/oauth-handoff?code=...&state=...`
- `apiFetch` exchanges the code with the gateway
- On success: reads `sessionStorage.pendingRedirect`, removes it, navigates to stored value (or `/dashboard` if absent)

## Acceptance criteria

- [ ] `docs/diagrams/frontend/sign-out-sequence.puml` exists and includes a `title` directive
- [ ] The sign-out diagram shows both the happy path and the error branch (server call fails, flow proceeds)
- [ ] `docs/diagrams/frontend/oauth-redirect-sequence.puml` exists and includes a `title` directive
- [ ] The OAuth diagram shows the `sessionStorage` write before the redirect and the read-clear-navigate on callback
- [ ] Both files are valid PlantUML (open `@startuml` / close `@enduml`, no syntax errors)

## Blocked by

- `.scratch/1.62-auth-route-guards/issues/02-sign-out-session-clearing.md`
- `.scratch/1.62-auth-route-guards/issues/03-oauth-sessionStorage-redirect-relay.md`
