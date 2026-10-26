# 020 — Phase 3 deepening (architecture candidates 1–3)

Branch: `issues/124-phase3-gate-fixes` (gate #124). Source: `/improve-codebase-architecture` report, `plans/019-phase3-gate.md`.
Flat layering rules hold (`.claude/rules/backend.md`): one `@Service` per concept, ports only for repositories, no new interfaces.

## 1. Drift lifecycle — one module

- Merge `DriftDetectionService` into `DriftService`: `list`, `resolve`, `sweepAll`, `sweep`. `DriftSweepScheduler` calls `DriftService.sweepAll`. Delete `DriftDetectionService`.
- Dependency side effects go through `DependencyService`, not raw `DependencyRepository` writes:
  - `DependencyService.declareObservedPair(tenantId, source, target)` — validates live nodes and self-edge, declares every live observed protocol not yet declared, returns false when none is observed (drift then throws `DriftNoLongerObservedException`, 409), marks the graph dirty after commit.
  - `DependencyService.removeDeclaredPair(tenantId, source, target)` — soft-deletes the declared pair, marks dirty.
  - `DependencyService.requirePairAccess(...)` becomes the public, documented access rule (was package-private `requireEitherSideAccess`, widened for drift).
  - Both pair ops join the drift transaction (`PROPAGATION_REQUIRED`), so the drift row and the dependency change commit or roll back together.
- Remove `DependencyDriftRepository.findActiveByEdge` (no production caller); ITs use `findAllActive`.
- Tests: resolve + sweep unit tests in `DriftServiceTest` / `DriftServiceSweepTest`; lifecycle (sweep → resolve → sweep) stays in `DriftControllerIT`.

## 2. Pending span-link join — deeper repository interface

`PendingSpanLinkRepository` shrinks to what callers mean; the per-tenant advisory lock is taken inside every method that reads or writes CLIENT/SERVER halves, so lock-before-lookup (ADR-0029 §5) can't be broken by a caller:

| New | Replaces |
|---|---|
| `Optional<PendingSpanLink> takeServerPartner(tenant, trace, clientSpanId)` — lock, find, delete | `lockTenant` + `findServerPartner` + `delete` |
| `Optional<PendingSpanLink> takeClientPartner(tenant, trace, parentSpanId)` | `lockTenant` + `findClientPartner` + `delete` |
| `void park(tenant, Collection<PendingSpanLink>)` — lock, batch insert | `insert` (one per row) |
| `Set<MessagingEndpoint> joinMessagingEndpoints(tenant, batch, expiresAt)` — upsert, lowercase names, return batch ∪ live stored | `upsertMessagingEndpoints` + `findMessagingEndpoints` |
| `deleteServerPartners`, `deleteExpired` | unchanged |

- `PendingSpanLink.protocol` becomes `DependencyProtocol` (DB string encoding stays in the Jdbc adapter).
- `ObservedDependencyService.FallbackLock` deleted.
- `pg_advisory_xact_lock` is re-entrant within a transaction, so repeated acquisition per batch is safe.
- Tests: `JdbcPendingSpanLinkRepositoryIT` covers take/park/join; service tests stop asserting `lockTenant` counts.

## 3. Unresolved-name lifecycle — `UnresolvedSpanServiceService` owns it

- `record(tenantId, Collection<String> names, Instant seenAt)` — case-insensitive dedupe, cap from `topology.span-links.max-unresolved-per-tenant`.
- `markRegistered(tenantId, name)` — called by `GraphNodeService.upsertNode`.
- `ObservedDependencyService` collects unknown names per batch and calls `record` once; it no longer touches `UnresolvedSpanServiceRepository`.

## Out of scope

Candidate 4 (pair identity) beyond the two pair ops above; perf items from `plans/019` (N+1, quadratic search).

## Done when

`./gradlew :services:topology:build` green (tests + checkstyle); no domain class imports `PendingSpanLinkRepository.lockTenant`/`UnresolvedSpanServiceRepository` outside its owner; CONTEXT.md and ADR-0029 references still accurate.
