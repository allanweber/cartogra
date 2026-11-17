# No database query inside a loop on user requests
Branch: `feat/phase5-contracts` (user's call)  ·  Issue: none yet

## Context
Rule: code that runs while a user waits for a response (a request from the frontend, including the service-to-service
calls it triggers) never runs a query once per item in a loop. Background work (schedulers, Kafka consumers) may loop.
The frontend sends one request per user action; the backend does the work.

## Fixes

### 1. Registry access check
Today: `services/registry/.../api/ServiceAccessInternalController.java:38` runs 2 queries per service id
(`findById` + `isMember`), no cap on ids. Called by topology during dependency declare/delete and drift resolve.

Fix: new `ServiceRepository.findAccessible(tenantId, userId, ids)`, one query; controller builds the map from the result.
```sql
SELECT s.id FROM services s
JOIN team_members m ON m.tenant_id = s.tenant_id AND m.team_id = s.team_id AND m.user_id = :userId
WHERE s.tenant_id = :tenantId AND s.id IN (:ids) AND s.deleted_at IS NULL
```
`ServiceAccessRequest.serviceIds` gets `@Size(max = 100)`. `ServiceService.isAccessibleBy` is removed if unused.

### 4. Declare observed dependency (drift resolve)
Today: `services/topology/.../domain/DependencyService.java:144` runs a lookup + insert per protocol.

Fix: `findLiveProtocols` stays (answers "nothing observed"); one insert of every observed protocol not declared yet:
```sql
INSERT INTO dependencies (id, tenant_id, source_service_id, target_service_id, dependency_type, protocol, created_at, updated_at)
SELECT gen_random_uuid(), :tenantId, :source, :target, 'declared', p, :now, :now
FROM unnest(:protocols) p
WHERE NOT EXISTS (SELECT 1 FROM dependencies d
                  WHERE d.tenant_id = :tenantId AND d.source_service_id = :source AND d.target_service_id = :target
                    AND d.dependency_type = 'declared' AND d.protocol = p AND d.deleted_at IS NULL)
```
2 queries total.

### 5. Save team
Today: `frontend/src/components/TeamDialog.tsx:166` works out the changes and sends one HTTP call per service and
member added or removed, plus the rename.

Fix: one request, `PUT /teams/{id}` with `{ "name": "...", "serviceIds": [...], "memberIds": [...] }` (the full
desired state). The frontend stops computing differences.

`TeamController` → `TeamService.update`, one transaction:
1. Same permission checks as today, once up front.
2. Load current services and members of the team (2 queries).
3. Work out added/removed (in memory).
4. Set owner on added services (1 `UPDATE ... WHERE id IN`).
5. Clear owner on removed services (1 `UPDATE ... WHERE id IN`).
6. History rows for every changed service (1 multi-row insert).
7. Add new members (1 multi-row insert).
8. Remove old members (1 delete).
9. Audit rows for every change (1 multi-row insert; new `JdbcAuditEventAdapter.recordAll`).
10. One Kafka event per changed service, as today (messages, not queries).

About 9 queries whatever the number of changes. Existing single-service owner endpoints stay for other screens.
Registry OpenAPI gets the new body fields.

## Out of scope
- Background loops: span ingest, drift sweep, health probe, sync scheduler, stale-job reaper, CODEOWNERS consumer,
  cluster startup.
- Resend verification / forgot password (duplicate emails across tenants): separate decision, not in this plan.

## Tests
- Each fix: IT with several items asserting the result and the statement count (counter on the test DataSource).
- Existing ITs stay green: registry access, drift resolve declare, team editing.
- Frontend: TeamDialog test asserts one `PUT` with `name`, `serviceIds`, `memberIds`; team e2e stays green.

## Verification
- `./gradlew build --continue`
- `cd frontend && pnpm test && pnpm lint`, team e2e
- Local k3d: `pnpm acme verify`

## Order
1 → 4 → 5.
