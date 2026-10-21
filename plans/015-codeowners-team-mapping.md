# 3.4 — CODEOWNERS team mapping (orphan risk from ownership events)
Issue: #122 (rescoped)  ·  Branch: `feat/3.4-codeowners-team-mapping`

## Context
Roadmap 3.4 asks Topology to consume `cartogra.ingestion.ownership.resolved` so ownership updates `graph_nodes.team_id`. ADR-0028 already showed that's moot: Registry `assignOwner` → `service.updated` → `GraphNodeEventConsumer` → `JdbcGraphNodeRepository.upsert` writes `team_id` (incl. NULL); `RiskService` reads `team_id IS NULL`; `pnpm acme verify` asserts the 3 orphans. A Topology consumer would need Registry's teams table to resolve handle → UUID (the round-trip 3.4 wants to avoid).

The real gap is the "check the team mapping" step — CODEOWNERS never maps to a team:
- GitHub provider emits `@acme/payments`; Registry `findByTenantAndName` does `lower(name) = lower('@acme/payments')` → never matches `Payments`.
- ADO provider keeps only handles starting `[` or containing `\` → Acme's `* @payments` yields `ownerTeams = []`.
- `CodeownersFlowIT` feeds bare `"platform-team"` directly, so the mismatch was never caught.

## Decisions
- **No Topology consumer**; ADR-0028 stands. Fix the CODEOWNERS → team mapping instead.
- **Split normalization**: each Ingestion provider strips its own handle syntax so `ownerTeams` carries bare team names; `pathOwners` stays raw (ADR-0015). Registry owns team identity: matches `slug(handle) == slug(team.name)`, slug = lowercase, runs of `[^a-z0-9]` → `-` (identical to Acme `teamSlug()`), computed in SQL — no migration.
- **Service lookup (added during implementation)**: `findByExternalId(repositoryFullName)` first, then the existing repo-URL suffix match. ADO `remoteUrl` is `.../<Project>/_git/<repo>` while the event carries `<Project>/<repo>`, so the URL suffix match alone never finds ADO services; SCM sync stores the full path as `external_id`.
- **Match rule**: resolve every handle; unmatched handles ignored; exactly one distinct team → assign; 0 → skip (warn); ≥2 distinct teams → skip as ambiguous. Already-owned services still skipped (unchanged).
- **Acme**: stays 🔎. Registry IT with Acme-shaped handles proves the mapping; README documents the manual SCM check; `pnpm acme load --skip-owners` added so CODEOWNERS is the only owner source in that check.
- **Issue**: rewrite #122 title/body + comment linking ADR-0028 — draft shown to user before posting.

## Implementation steps
1. **Ingestion — handle parsing**
   - `services/ingestion/src/main/java/io/cartogra/ingestion/infrastructure/scm/CodeownersHandles.java` (new, package-private-ish static helper): `Optional<String> teamName(String handle)` — `@org/team` → `team`; `[Project]\Team` / `Project\Team` → `Team`; `@team` (no slash) → `team` only when caller allows bare handles; emails → empty.
   - `infrastructure/scm/github/GitHubProvider.java#resolveOwnership` (L128-150): keep `@org/team` filter (GitHub bare `@x` is a user), map via helper, dedupe.
   - `infrastructure/scm/azuredevops/AzureDevOpsProvider.java#resolveOwnership` (L128-150): accept `[P]\X`, `P\X`, `@x`, `@org/x`; map via helper, dedupe.
   - Update `services/ingestion/CONTEXT.md` payload row: `ownerTeams[]` = bare team names.
2. **Registry — slug lookup + match rule**
   - `repository/TeamRepository.java`: replace `findByTenantAndName` usage for CODEOWNERS with `Optional<Team> findByTenantAndSlug(UUID tenantId, String slug)` (keep `findByTenantAndName` if used elsewhere — currently only `ServiceService:466`; remove if then unused).
   - `infrastructure/jdbc/JdbcTeamRepository.java`: `WHERE tenant_id = :tenantId AND regexp_replace(lower(name), '[^a-z0-9]+', '-', 'g') = :slug AND deleted_at IS NULL`.
   - `domain/Team.java` (or small static in `domain`): `static String slug(String name)` — same regex in Java.
   - `domain/ServiceService.java#resolveOwnership` (L438-476): map `ownerTeams` → `findByTenantAndSlug(slug(h))`, collect distinct team ids; branch 0 / 1 / ≥2 per rule; update Javadoc (one sentence on the match rule).
   - Update `services/registry/CONTEXT.md` ownership rule row.
3. **Acme seed**
   - `seed/acme-fintech/src/load.ts`: `LoadOptions.skipOwners?: boolean`; skip `assignOwner` block when set.
   - `seed/acme-fintech/src/cli.ts`: `load --skip-owners` flag.
   - `seed/acme-fintech/README.md` §4b: manual check — fresh tenant, `pnpm acme repos`, `pnpm acme load --skip-owners`, connect ADO + sync, expect 17 owned / 3 orphans in `/risks`. Note caveat: ownership resolves via `findByRepositoryPath`, so services must carry the repo path (SCM-discovered); verify during the manual run and record result.
4. **Docs**
   - `docs/roadmap.md` 3.4: retitle "CODEOWNERS team mapping", reference ADR-0028, drop the Topology-consumer wording; Acme line keeps 🔎 with the new check. Gate line 250 → "A `service.updated` that clears `teamId` updates `graph_nodes.team_id`, and the orphan appears in `GET /v1/risks`; CODEOWNERS handles resolve to Registry teams."
   - `CONTEXT-MAP.md` orphan-risk note: #122 rescoped to team mapping.
   - `docs/architecture/kafka-topics.md` L85: mark `ownership-changed` row as not built (ADR-0028), conflict resolved.
5. **Issue #122**: draft new title/body + comment → user approves → `gh issue edit 122` / `gh issue comment 122`.

## Acceptance criteria
- [ ] GitHub `* @acme/payments` → `ownerTeams = ["payments"]`; bare `@alice` and emails excluded.
- [ ] ADO `* @payments`, `* [Acme]\Payments`, `* Acme\Payments` → `ownerTeams = ["payments"|"Payments"]`; emails excluded.
- [ ] Registry assigns team `Payments` for handle `payments`/`Payments`; `Platform Team` matches `platform-team`.
- [ ] `["payments", "alice"]` (one team match) → assigned; `["payments", "ledger"]` → skipped as ambiguous; no match → skipped; already-owned → skipped.
- [ ] Acme-shaped IT: 5 teams, 20 repos, 17 with CODEOWNERS → 17 owned, 3 `team_id IS NULL`.
- [ ] Topology: owned node + `service.updated` with `teamId = null` → `team_id` NULL and node in orphan query.
- [ ] `pnpm acme load --skip-owners` assigns no owners; default load unchanged (idempotency check still passes).
- [ ] Roadmap 3.4, gate line, CONTEXT-MAP, CONTEXT.md files, kafka-topics.md updated; #122 rescoped.
- [ ] No new Kafka topic, no Topology consumer, no migration.

## Tests
- **Unit (ingestion)**: `CodeownersHandlesTest` (all handle shapes); extend `GitHubProviderTest` (L93 now expects `platform`, `backend`) and `AzureDevOpsProviderTest` (`@x`, `[P]\X`, mixed with user/email).
- **Unit (registry)**: `ServiceServiceTest` resolveOwnership cases (L509/L575 move to `findByTenantAndSlug`): single match, user+team, two teams, none, already owned; `Team.slug` cases.
- **Integration (registry)**: `JdbcTeamRepository` slug lookup (case, spaces, tenant isolation, soft-deleted excluded); fix `CodeownersFlowIT` to send realistic handle; new `AcmeCodeownersMappingIT` (or a case in `OwnershipResolvedConsumerIT`) with Acme teams + handles → 17/3.
- **Integration (topology)**: `GraphNodeEventConsumerIT` new case `updatedEventClearingTeamMakesNodeOrphan`.
- **Seed**: `seed/acme-fintech/test/load.test.ts` — `skipOwners` assigns nothing.
- **E2E (Playwright)**: none — no UI change; `/risks` orphan rows already covered by `acme verify`.

## Verification
```bash
./gradlew :services:ingestion:test :services:registry:build :services:topology:build
./gradlew build
cd seed/acme-fintech && pnpm test
pnpm acme verify      # against local stack — orphans still 3, second load no-op
```
Manual (🔎): README §4b CODEOWNERS check against a real ADO org.

## Out of scope
- CODEOWNERS changes propagating (A→B reassign, removal → orphan); needs `ownership_source` (MANUAL vs CODEOWNERS).
- Topology consumer of `ownership.resolved` / ADR-0027 topic.
- `teams.slug` column / unique constraint.
- Automating the Acme SCM check in `verify`/CI.
- GitHub individual-user (`@alice`) ownership.
