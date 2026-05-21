# ADR-0015 — CODEOWNERS persistence shape

**Status**: Accepted
**Date**: 2026-05-20
**Deciders**: Allan Weber

---

## Context

The `ScmProvider` SPI declares a `resolveOwnership(config, repository) → OwnershipMap` method, with concrete implementations in `GitHubProvider` (parses `CODEOWNERS`) and `AzureDevOpsProvider` (queries Azure DevOps team / required-reviewer metadata). The product scope ([docs/project-scope.md:38](../project-scope.md), [docs/project-scope.md:223](../project-scope.md)) and ADR-0002 commit to using this data to drive service-to-team ownership in the registry.

Today, `ExecuteSyncUseCaseImpl` calls `resolveOwnership` for every non-archived repository on every sync and throws the result away. This burns SCM API rate-limit budget for zero observable effect, and the registry has no path to consume the data even if it were preserved.

Two persistence shapes are viable:

| Option | Storage | Match semantics |
|--------|---------|-----------------|
| **(a) Auto-assign on `services.team_id`** | Existing `services` row | Look up tenant `Team` by name from `OwnershipMap.ownerTeams`. If exactly one match and `services.team_id IS NULL`, set it. Otherwise emit an `audit_events` row (action `codeowners.unmatched` or `codeowners.ambiguous`) and leave the service unassigned. |
| **(b) Dedicated `service_codeowners` table** | New tenant-scoped table mapping `(service_id, path_glob, team_id)` | Each row preserves path-level ownership; the existing `services.team_id` becomes a denormalised summary. Useful when CODEOWNERS expresses owner per directory. |

The audit identified this as **dead-end 1** in the phase 1–5 review: a producer with no consumer.

---

## Decision

**Recommend option (a) — auto-assign on `services.team_id`** with an `audit_events`-driven fallback for ambiguous matches.

Rationale:

1. **MVP fit**: the existing `Service` domain has a single `teamId`; the catalog UI, orphan detection, and ownership use cases all assume "one team per service". A path-level breakdown is a Phase 4+ enrichment, not Phase 1.
2. **Idempotency is cheap**: every sync recomputes the same `OwnershipMap` for the same repo; "set only if null, otherwise audit" is the safest rule against repeated runs.
3. **No new migration today**: option (a) requires zero new schema. Task 1.39d collapses to a closed-with-note item.
4. **Reversibility**: if Phase 4 work needs path-level owners, a follow-up ADR can add the `service_codeowners` table without removing or renaming the existing column.

If option (b) is later required, the migration path is additive and does not block Phase 1.

The dead-call cleanup at [ExecuteSyncUseCaseImpl.java:69-73](../../services/ingestion/src/main/java/io/cartogra/ingestion/application/usecase/ExecuteSyncUseCaseImpl.java#L69-L73) is replaced by the publisher in task 1.39e; the consumer is task 1.39f.

---

## Consequences

### Positive

- The currently-discarded `OwnershipMap` becomes load-bearing — every existing line of code in `GitHubProvider.resolveOwnership` and `AzureDevOpsProvider.resolveOwnership` earns its place.
- Service catalog ownership populates automatically for new tenants without manual `/assign-owner` calls.
- No new schema in Phase 1.

### Negative / Trade-offs

- Path-level CODEOWNERS data is dropped (only the repo-level `ownerTeams` is consumed). For monorepos where one repo hosts multiple services, this is lossy. Mitigation: a follow-up ADR adds the path table if a monorepo customer needs it.
- Ambiguous matches require `audit_events` to be queryable, which only lands in 2.34/2.35. Until then, ambiguous matches are logged at WARN — acceptable for Phase 1.

### Neutral

- The `OwnershipMap` record gains no new fields. The `pathOwners` map is preserved on the wire (in the `cartogra.ingestion.ownership.resolved` envelope) so a future consumer can read it without changing the SPI.

---

## Alternatives Considered

| Option | Reason rejected |
|--------|-----------------|
| Option (b) — dedicated `service_codeowners` table | Premature for Phase 1; the catalog UI and orphan detection only need a single team per service today. |
| Delete `resolveOwnership` from the SPI | Violates the product scope and ADR-0002 commitments. The feature has documented user-facing value; the gap is the consumer, not the producer. |
| Resolve ownership lazily on read (REST call to ingestion when registry serves a service detail) | Couples the registry's hot read path to a slow SCM API; defeats the point of having ingestion be the async layer. |

---

## References

- ADR-0002 — SCM provider abstraction via SPI
- [docs/project-scope.md:38](../project-scope.md) — ownership tracking via CODEOWNERS / AzDO teams
- Task 1.39c (this ADR), 1.39d (migration if needed), 1.39e (publisher), 1.39f (consumer), 1.39g (test)
