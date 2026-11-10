# 023 — Phase 4 gate (issue #130)

Branch: `docs/phase-4-plan` (plan 021 step 8). Scope: Phase 4 changes, `git diff d2191af...HEAD` (written against `a4180d8`).
Runs: `/code-review`, `/security-review`, `/impeccable audit`, `/impeccable critique`, `/improve`, `/improve-codebase-architecture`.

## Gate evidence

| Gate item | Evidence |
|---|---|
| Public URL serves Acme over TLS; runbook rebuilds the host from scratch (VM → k3s → helm → bootstrap Job) | First build of `cartogra.dev` on 2026-11-10 followed `docs/runbooks/deployment.md` §1–8 via `infra/k8s/demo-host-wizard.sh`; TLS Ready, 21 Acme pods, bootstrap Job done. Restore (§10) has never been run against a real dump. |
| `/demo` gets a stranger in; no self-registration | Live: `/demo` 200; `GET /api/auth/features` → `signupEnabled:false, guestEnabled:true`; `POST /api/auth/register` → 403 `SIGNUP_DISABLED` |
| Guest reads succeed, every mutating endpoint 403, IT enumerates routes | `WriteAuthorizationIT` (registry, topology, ingestion) enumerate via `WriteRoutes.of(context)` (`@PreAuthorize` layer, roles injected). Gateway `GuestModeIT` enumerates its own routes; proxied writes are a hand list (`PROXIED_WRITE_PATHS`, 6) — sufficient because `GuestWriteDenyFilter` blocks by HTTP method, not route. Live: guest `GET services` 200, `POST services`/`POST teams` 403. |
| Dashboard and Timeline mocks deleted; `mock-data.ts` only for unshipped screens | `dashboard.tsx`, `timeline.tsx` have no `MOCK_*`. Gate found `CommandPalette` still listing `MOCK_TEAMS` and an unused `MOCK_EVENTS` — **fixed**: palette reads `/v1/registry/teams` (+ owned-service count); `mock-data.ts` keeps only `MOCK_CONTRACTS` (Contracts, Phase 5). |
| Audit events from registry, topology, ingestion, gateway; paginated; readable by guests | `AuditEventIT`, `DependencyControllerIT`, `ScmConnectionAuditIT`, `AuthControllerIT` (user/invitation rows). Live demo: service 82, team 5, dependency 85, drift 1, scm_connection 5 (no gateway events there — no user changes happen on the demo). Guests get the feed minus `user`/`invitation`/`oidc_config`/`cluster` and without actor ids (ADR-0019 §8). |
| Build green | `./gradlew build` ✅ (after all gate fixes); frontend 48 files / 462 tests ✅; `tsc`, eslint ✅; `helm lint` demo values ✅; `infra/helm/local-test.sh` on k3d ✅ (incl. NetworkPolicy, alerting, capacity checks). E2E runs in CI on the PR. |

## /code-review (Standards + Spec)

Spec: no missing gate item. Scope creep noted: under-development banner (`4548451`), #218 K8s tenant fix (name it in the PR), 7a/7c not in roadmap 4.5 (doc gap).

| Finding | Triage | Change |
|---|---|---|
| `demo.tsx` writes via `useEffect` + raw promise; error in raw `<p>` (frontend.md: `useMutation`, shadcn `Alert`) | **fixed** | `useMutation`, `Alert` with trace id; test asserts `role=alert` |
| `TenantBootstrapProperties` without `@Validated` | **fixed** | `@Validated`, `@Email` on `adminEmail` |
| `SignupProperties` without `@Validated` | won't-do | one boolean, nothing to validate |
| Kafka `-Xms/-Xmx` hard-set (infra.md) | **fixed** | `Initial/MaxRAMPercentage` 50 (base, = 512m of 1Gi), 60 on demo (≈384m of 640Mi); verified on k3d |
| New query keys not tenant-scoped | won't-do | repo-wide convention (Phase 3 gate); now backed by `queryClient.clear()` on every session change (see /improve) |
| postgres/valkey/collector `runAsUser` ≠ 1000 | won't-do | third-party image UIDs |
| SecurityConfig ×3 duplicates, role strings, `guest ? r.withoutX() : r`, audit data clumps | next-phase | /improve-codebase-architecture #2, #4 |
| Third-party actions pinned by tag in images/deploy/helm workflows | next-phase | SHA-pin like `ci.yml` |

## /security-review

`origin/main...HEAD` (7 commits not yet on main): no finding ≥ 8/10. Rejected with reasons: sandbox gate fails closed; deployer `create` on clusterroles blocked by RBAC escalation prevention (no `escalate`/`bind`); NetworkPolicy same-namespace `podSelector` blocks cross-namespace header forging; alerting SA read-only `nodes/metrics`; SMTP key file-mounted. Hardening, next-phase: NetworkPolicy on the alerting pod (Prometheus/Alertmanager open in-cluster) and on postgres/kafka/valkey (pre-existing).

## /impeccable audit + critique — UI

Detector: 0 findings on 25 phase files (regex); rendered scan of `https://cartogra.dev/demo` → 1 advisory. Critique condensed (no screenshot sub-agents).

| Finding | Sev | Triage | Change |
|---|---|---|---|
| Guest banner "Sign in" link 4.3:1 (`text-primary` on `bg-warning-subtle`) — every demo visitor sees it | P1 | **fixed** | ink + underline (`decoration-foreground/40`) |
| Timeline search covers only loaded pages; "No events match" while 158 of 178 are unloaded | P1 | **fixed** | "No match in the N loaded of M events. Load more to search further."; `timeline.test.tsx` |
| Command palette lists fictional teams with "members · lead" | P1 | **fixed** | real teams (gate item above) |
| `ReadOnlyGuard` wraps a disabled button in a focusable `span` with no role/name | P2 | next-phase | `aria-disabled` on the control instead of `disabled` + wrapper |
| Timeline categories coded with status hues (drift red, team/access amber); service and dependency share info blue | P2 | next-phase | DESIGN.md reserves status hues for health/severity |
| Timeline abbreviation circles ("svc", "dep") duplicate the badge for AT; skeleton `aria-label` on a role-less div; list not `<ol>` | P3 | next-phase | |

## /improve — triage

| Finding | Triage | Change / reason |
|---|---|---|
| Session change kept the previous session's cache (guest demo → login showed demo-tenant data for up to 5 min; `['teams','mine']` carried permissions) | **fixed** | `queryClient.clear()` on logout, login, OAuth handoff, accept-invite; `login.test.tsx` |
| Timeline: a failed "Load more" hid every loaded event | **fixed** | list stays while `events.length > 0` |
| Timeline: offset paging over a growing log repeats events | **fixed** (stopgap) | dedupe by `eventId`; cursor paging next-phase |
| Stuck `pending-upgrade` release after a cancelled deploy; rollback of `cartogra` alone leaves `acme` on newer SHA | **fixed** (docs) | `deployment.md` §9: prefer redeploying the old SHA; stuck-release recovery |
| No alert when the nightly backup fails | next-phase | needs kube-state-metrics or a pushed success timestamp; demo data is re-seedable |
| Restore never exercised against a real dump | next-phase | restore check in `local-test.sh` |
| Helm CI doesn't schema-validate rendered manifests | next-phase | `kubeconform -strict` |
| Audit list: `COUNT(*)` per page, unbounded offset | next-phase | with cursor paging |
| Guests see raw `sync_failed` error text | **fixed** | `error` is not a guest field of `AuditKind.SCM_CONNECTION` (architecture #1) |
| No rollback / broker-down tests for the audit adapters | next-phase | S |
| Kafka audit sends untracked: no failure counter, no drain on shutdown | next-phase | ADR-0017 accepts loss; add a metric |
| Re-minting resets a guest's rate bucket (5 mints/min/IP) | next-phase | bucket per IP for guests |
| Bootstrap race across replicas untested | won't-do now | demo runs 1 gateway replica |
| `GuestService.enabled()` reads the DB per call | won't-do | documented: clearing the flag stops mints at once |
| GIN index on `audit_events.payload` unused | won't-do | asked for by plan 021 step 2 (payload queries planned) |

## /improve-codebase-architecture — candidates

Report: `architecture-review-20261110.html` (session scratchpad, not in repo).

| Candidate | Strength | Status |
|---|---|---|
| Audit entity kinds as one module — a producer string + registry denylist meant a new personal payload key leaked to guests silently | Strong | **fixed** — `AuditKind` enum (shared/common): wire name + guest-visible payload fields; `AuditEvent.of` takes a kind; registry guest view = `entity_type IN (visible kinds)` + `AuditKind.guestPayload` (top level and inside `changes`); unknown kinds never reach guests. `AuditKindTest`, `GuestRedactionIT.guestAuditFeedIsAnAllowlistNotADenylist`; ADR-0019 §8 amended |
| Actor carries the role tier; one shared security-chain builder (collapses `GuestCaller`, 3 `SecurityConfig`s, role strings) | Strong | next-phase (user, 2026-11-10): cross-service refactor, Phase 5 |
| One frontend permission module — a VIEWER saw enabled write buttons that 403 (topology, register service, and Edit/dependencies when in the owning team: `service.edit` ignored the write tier) | Strong | **fixed** — `ReadOnlyGuard` → `PermissionGuard action context` (allow / disabled+reason / hidden); new actions `service.create`, `drift.resolve`, `unresolved.dismiss`; `service.edit` = writer ∧ member; all 13 write controls go through it; role × action tests in `permissions.test.ts`, `PermissionGuard.test.tsx` |
| One audit recorder per context | Worth exploring | next-phase (builds on `AuditKind`) |
| Shared audit transport auto-configuration | Speculative | next-phase |
