# 019 — Phase 3 gate (issue #124)

Branch: `issues/124-phase3-gate-fixes`. Scope: Phase 3 changes only, `git diff 5657c2c..HEAD` (written against `9db309f`).
Runs: `/impeccable audit`, `/impeccable critique`, `/improve`, `/improve-codebase-architecture`, `/code-review`, `/security-review`.

## Gate evidence

| Gate item | Evidence |
|---|---|
| Synthetic span fixture → observed edge, no collector | `OtelSpanWorkerIT` (fixtures in `services/topology/src/test/resources/otel/`) |
| Undeclared + missing drift raised; accept/remove/dismiss ITs | `DriftControllerIT` (11), `JdbcDependencyDriftRepositoryIT`, `DriftDetectionServiceTest` |
| Acme loader twice → identical state | `seed/acme-fintech/test/load.test.ts` "leaves identical state when run twice"; live: `pnpm acme verify` |
| `service.updated` clearing `teamId` → orphan in `/risks`; CODEOWNERS → teams | `GraphNodeEventConsumerIT.updatedEventClearingTeamTurnsNodeIntoOrphanRisk`, `AcmeCodeownersMappingIT` |
| No topic without a consumer | Topology produces only the span DLQ; lifecycle events deferred (3.5 → 6.7) |

## /impeccable audit + critique — UI

Detector: 0 findings on all 7 Phase 3 files. Browser overlay not run: Claude in Chrome extension not connected (source-only review, recorded).

| Finding | Sev | Triage | Change |
|---|---|---|---|
| Inspector Drift tab shows tenant-wide drift, not the selected service's | P1 | fixed | `DriftTab` `serviceId` filter + per-service empty state |
| Additive Accept confirmed with red destructive button | P1 | fixed | `ConfirmDialog` `tone` prop; Accept = default |
| No legend for drift edge styles | P1 | fixed | `DriftLegend` beside the edge-mode toggle |
| Selecting a drift whose edge is hidden in current mode does nothing | P2 | fixed | `selectDrift` switches to Drift mode |
| Side card titled "Drift" with a blast-radius hint | P2 | fixed | Hint on top; "Drift (n)" and "Unresolved services (n)" sections |
| Repeated "Accept"/"Dismiss"/"Create service" with no context for AT | P2 | fixed | `aria-label` with pair / service name |
| Unlabelled "412× · 2m ago"; "1m ago" for seconds | P3 | fixed | "412 calls · last seen 2m ago", "just now" |
| Silent caps (drifts 200, unresolved 200) | P3 | fixed | "Showing the first N of M" notes |
| Edges have no direction arrows (drift is directional) | P2 | next-phase | pre-existing graph rendering (Phase 1) |
| All mode overlays declared + observed edges for the same pair, indistinguishable | P2 | next-phase | style observed-only edges / title by type |
| No bulk drift triage; Dismiss has no undo | P2 | next-phase | direction item; needs an un-dismiss endpoint |
| Buttons `size="sm"` < 44 px | P3 | won't-do here | systemic; `pointer-coarse:h-11` already enlarges on touch |

## /improve — triage

| Finding | Triage | Change / reason |
|---|---|---|
| `DriftDetectionService.sweep` `@Transactional` bypassed (self-invocation) + resolve race raising false drift | **fixed** | `TransactionTemplate`, REPEATABLE READ per tenant; test asserts isolation + commit |
| ACCEPT_OBSERVED records "accepted" with nothing declared when the observed edge expired; `markDirty()` before commit | **fixed** | 409 `DRIFT_NO_LONGER_OBSERVED` rolls back; `markDirty()` after commit |
| Team slug collision: `LIMIT 1` picks oldest team, bypassing ambiguity guard | **fixed** | `findByTenantAndSlug` returns all matches → ambiguous → skip |
| Edge upserts in arrival order → possible deadlock across instances | **fixed** (edges) | sorted lock order; messaging endpoint batch → next-phase |
| Topic name hard-coded twice | **fixed** | `SpanLinkConfig.SPANS_TOPIC` used by the listener |
| `OtlpSpanMapper` violates no-`*Mapper` rule | **fixed** | renamed `OtlpSpanParser` |
| CONTEXT.md lists produced topics that don't exist; `pending_span_links` + `span-links.*` undocumented/unconfigurable | **fixed** | CONTEXT.md + `application.yml` env placeholders |
| Roadmap Acme counts stale (20/40 vs 21/43) | **fixed** | `docs/roadmap.md` |
| `verify` counts every declared edge in tenant (breaks after a demo Accept); case-sensitive teams | **fixed** | compares seed edge keys; case-insensitive teams |
| `drift on/off` port-forward can hang forever | **fixed** | 30 s timeout |
| Seed package/image not in CI, no Trivy | **fixed** | `acme-seed` CI job + image scan in container-build |
| Temporary `push` trigger left in `perf.yml` | **fixed** | removed (flagged by security review) |
| Partial-failure replay re-parks pending rows / inflates `seen_count` (no unique index on pending CLIENT/SERVER) | next-phase | M; migration + ON CONFLICT; multi-tenant records are rare (collector is per tenant) |
| Per-span lock + SELECT/INSERT on unresolved targets; N+1 upserts; quadratic in-batch search; unbounded reaper DELETE | next-phase | perf, watch `cartogra.spans.*` before optimising |
| DLQ partition = source partition | next-phase | investigate deployed partition counts |
| Refresh-token reuse only detected one rotation back | next-phase | hardening (security review: rejected, not bypass); needs hash history |
| ADO handles with spaces truncated; GitHub org qualifier dropped | next-phase | confirm ADO quoting rules; pass org through |
| Any user can permanently dismiss unresolved names | won't-do now | recorded decision (plan 017); revisit with 4.3 guest/RBAC audit |
| Secrets as env vars in k8s manifests | next-phase | all 4 services, configtree import; with Phase 7 infra |
| `CARTOGRA_TENANT_ID=unset` silently drops spans | next-phase | DX warning |
| Query keys not tenant-scoped | won't-do | repo-wide convention; cache cleared on logout/tenant switch |
| Race ITs (advisory lock, concurrent refresh, sweep vs resolve) | next-phase | M; Testcontainers latch tests |

## /improve-codebase-architecture — candidates

Report: `architecture-review-*.html` in the OS temp dir. #1–#3 chosen and done; design in `plans/020-phase3-deepening.md`.

| Candidate | Strength | Status |
|---|---|---|
| Drift lifecycle as one module (detect + resolve + transitions; accept/remove via `DependencyService`, un-widen `requireEitherSideAccess`) | Strong | **fixed** — `DriftDetectionService` merged into `DriftService`; `DependencyService.declareObservedPair` / `removeDeclaredPair` / public `requirePairAccess`; dead `findActiveByEdge` removed |
| Pending span-link join behind a deeper repository interface (lock, TTL, delete-on-match, encoding inside) | Strong | **fixed** — `takeClientPartner` / `takeServerPartner` / `park` / `joinMessagingEndpoints` take the tenant lock themselves (once per transaction); `FallbackLock` gone; real two-transaction race IT added |
| Unresolved-name lifecycle owned by `UnresolvedSpanServiceService` | Worth exploring | **fixed** — `record` (sorted, case-insensitive dedupe, cap) + `markRegistered`; span pipeline and graph-node sync no longer touch the repository |
| Pair/edge identity owned by the dependency module | Speculative | partly covered by the pair ops; rest won't-do |

## /code-review, /security-review

- `/security-review`: no HIGH/MEDIUM findings ≥ 8/10 confidence. Excluded-but-noted: k8s secrets as env vars (above), perf-user password in `infra/perf/login.sh` (throwaway CI user), RLS not verified on V011/V014 tables (queries all filter `tenant_id`).
- `/code-review high 5657c2c..HEAD` (first run mis-targeted the Phase 2 merge commit `5657c2c` itself; its findings are Phase 2 code and out of scope):

| Finding | Triage | Change |
|---|---|---|
| UNDECLARED raised for long-silent observed pairs, so REMOVE_DECLARED bounces back as UNDECLARED | **fixed** | undeclared needs `lastActiveAt` within `missing-after`; test added |
| Unresolved-name row locks taken before and after the tenant advisory lock → deadlock | **fixed** | names recorded once at batch end, after any lock, in sorted order |
| REPEATABLE READ sweeps on several replicas fail each other | **fixed** | `DriftSweepScheduler` takes a cluster advisory lock |
| Messaging endpoint upserts in arrival order → deadlock | **fixed** | sorted inside `joinMessagingEndpoints` |
| Per-service Drift tab silent when drifts are past the first 200 | **fixed** | capped note also shown per service |
| Drift heading count uses page length, shows 0 on error | **fixed** | uses `total`, hidden until loaded |
| 409 `DRIFT_NO_LONGER_OBSERVED` leaves a stale row | **fixed** | refetches drifts on that code |
| `DriftLegend` defined in the route file | **fixed** | `components/DriftLegend.tsx` |
| Client `peer.service` recorded as unresolved before its SERVER half arrives in a later batch | next-phase | needs the peer name stored on the parked row (migration); ADR-0029 §7 documents same-batch only |
| k8s secrets as env vars | next-phase | see /improve row |

## Content

`docs/bip/3.3-declared-vs-observed-gap.md`, `docs/bip/3.1-otel-spans-to-graph-edges.md`, `docs/bip/3.3-drift-as-a-workflow.md`.
Screen test plan: `docs/runbooks/phase-3-acme-screen-test-plan.md`.
