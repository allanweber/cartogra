# Team membership (live DB check) replaces the TEAM_OWNER role

Supersedes ADR-0022. ADR-0022 planned to keep `TEAM_OWNER` as a JWT role and tighten it with a `team_members` table once one existed. Instead, `TEAM_OWNER` is dropped entirely: a `team_members` table (child of the `Team` aggregate, all members equal — no lead) now backs authorization directly. Editing a Service (`PUT /services/{id}`) and managing a Team (rename/delete/add-member/remove-member) both require the requester to be ADMIN or a member of the Team in question, checked live against `team_members` on each request — not via a JWT claim.

We chose live DB checks over baking team IDs into the JWT (the original ADR-0022 sketch) because the gateway's JWT is valid for 15 minutes; a claims-based approach would leave membership changes stale for up to that long, and would require the gateway (which issues tokens) to read a table it doesn't otherwise know about. The registry service already owns `Team` and already receives `X-User-Id` per request, so it can check membership itself with no new cross-service coupling.

Creating a new Team is ADMIN-only, since a brand-new Team has no members yet to authorize against. A Service with `team_id IS NULL` (orphan) has no owning Team, so only ADMIN can edit it — team membership can never satisfy that check.

## Consequences

- `TEAM_OWNER` is removed from the role vocabulary; the `@PreAuthorize("hasAnyRole('ADMIN', 'TEAM_OWNER')")` on `ServiceService.update` is replaced with a programmatic ADMIN-or-team-member check.
- Every team-management and service-edit mutation now costs one extra membership lookup; acceptable given these are low-volume admin actions, not hot paths.
- Team membership changes take effect immediately, with no token refresh needed.

## Related: team count plan limit and invitations

Shipped alongside this change: `billing_plans.max_teams` (free=2, business=25, enterprise=unlimited) is enforced in `TeamService.create`, mirroring the existing `max_services` pattern (402 + `PLAN_LIMIT_EXCEEDED`). Separately, Gateway's `max_users` limit — present on `billing_plans` since Phase 0 but never enforced — is now checked on the OAuth existing-tenant join path and on the new ADMIN-initiated invitation flow (`POST /api/auth/admin/invite`). An invitation is a `users` row with no `password_hash`, `email_verified = false`, and an opaque URL-safe invite token (7-day TTL, same generation style as refresh tokens); accepting it (`POST /api/auth/accept-invite`) sets the password, verifies the email, and — if the invite carried a `teamId` — attaches team membership at that point via a direct JDBC write to Registry's `team_members` table (same Phase-0-compromise DB access Gateway already has for `users`/`tenants`, not a new synchronous service-to-service call).
