# Detect services removed from the cluster
Issue: #234 · Branch: `fix/detect-removed-services`
Plan file in repo: `plans/030-detect-removed-services.md`

## Context
The registry gets only "present" messages from the K8s worker. Thus a Service that is gone keeps `k8s_cluster` forever:
- `KubernetesWorker` ignores the Service `DELETED` event.
- For an Endpoints `DELETED` event, the worker cannot find the Service, so it sends nothing.
- `resync()` sends only the Services that are present.
- `ServiceService.upsertDiscovered` keeps the current value when a command field is null.

The next task ("running in cluster, no owner") needs this rule: "`k8s_cluster IS NOT NULL` means that the Service is in the cluster now."

Facts found:
- The registry keys rows by the cluster NAME (`findByK8sIdentity`).
- `k8s_clusters.name` has no unique index (V003).
- A cluster rename and a cluster delete also leave stale values.
- `upsertDiscovered` does not write when nothing changed, so `updated_at` changes only on a real change.

## Decisions
- **Removal effect**: The registry keeps the row (owner, tier, and history stay). It sets `k8s_cluster`, `k8s_namespace`, `k8s_deployment`, and `health_endpoint` to NULL, and `health_status` to `UNKNOWN`. `source` stays `kubernetes`. The registry writes a history snapshot and the audit action `discovery_removed`, and sends `service.updated`. Reason: user data stays, and the meaning of `k8s_cluster` is "in the cluster now".
- **Topic**: `cartogra.ingestion.service.discovered` becomes `cartogra.ingestion.service.changed` (the rule in plan 024: one topic for each entity). Plain cutover, as in plan 024. Event types:
  - `service.discovered`: the payload does not change. Key = `cluster/ns/name`.
  - `service.removed`: `{tenantId, k8sCluster, k8sNamespace, name}`. Key = `cluster/ns/name`.
  - `service.resynced`: `{tenantId, k8sCluster, observedAt, services: [{namespace, name}]}`. Key = cluster name. No new topic. The order against the per-service events is not important because of the `observedAt` guard.
- **Watch DELETED**: The worker sends `service.removed`. If no row matches the K8s identity, the registry does nothing (idempotent).
- **Resync**: After a full list, the worker sends `service.resynced`. The registry sets the K8s fields to NULL only if the row has the cluster name, the row is not in the set, and `updated_at < observedAt`. This guard prevents a race with a Service that was created after the list.
- **Partial failure**: If one list call fails during the resync, the worker does not send the snapshot. Reason: a partial set would remove the K8s fields of rows that are present.
- **Namespace without the label**: Its Services are not in the set, so their rows lose the K8s fields. This is correct: the namespace is not in the tenant scope now.
- **Cluster delete and rename**: `ClusterService.delete` sends `service.resynced` with an empty set for the cluster name. `ClusterService.update` does the same for the old name if the name changes. The resync of the new worker then claims the rows again through `findByNameForK8sClaim` (`k8s_cluster IS NULL`).
- **Unique cluster name**: A new ingestion migration adds a unique index on `(tenant_id, lower(name)) WHERE deleted_at IS NULL`. A duplicate name on create or update gives 409 + `ErrorCodes.CONFLICT`. Reason: two clusters with one name would remove the K8s fields of each other at each resync.

## Implementation steps
1. **Ingestion migration** → `services/ingestion/src/main/resources/db/migration/V006__unique_k8s_cluster_name.sql`: `CREATE UNIQUE INDEX ... ON k8s_clusters (tenant_id, lower(name)) WHERE deleted_at IS NULL`.
2. **Duplicate name** → `domain/exception/DuplicateClusterNameException.java`. `ClusterService.create`/`update` examine the name through a new `ClusterRepository.existsByName(tenantId, name, excludeId)` (impl in `infrastructure/jdbc/JdbcClusterRepository.java`). `api/GlobalExceptionHandler.java` maps it to 409 `CONFLICT`.
3. **Producer** → rename `infrastructure/kafka/ServiceDiscoveredProducer.java` to `ServiceChangedProducer.java`:
   - `TOPIC = "cartogra.ingestion.service.changed"`.
   - Methods: `publishDiscovered(ServiceDiscoveredPayload)`, `publishRemoved(ServiceRemovedPayload)`, and `publishResynced(ServiceResyncedPayload)`. Each method sets the `event_type`.
   - Keep the `traceparent` injection and the key logic.
   - New payload records go in `domain/`: `ServiceRemovedPayload`, `ServiceResyncedPayload`, `PresentService`.
4. **Worker** → `infrastructure/k8s/KubernetesWorker.java`:
   - In the service watch, send `Action.DELETED` → `publishRemoved`, but only if the namespace belongs to the tenant. If the namespace was deleted, it is gone, so send the removal anyway. The registry match by cluster+ns+name keeps it safe.
   - `resync()`:
     1. Record `observedAt` before the list.
     2. Collect the present set.
     3. If a list call throws an exception, send no snapshot and log a warning.
     4. If all list calls succeed, call `publishResynced`.
   - Update the callers in `ClusterWorkerManager.java`.
5. **Cluster ops** → `domain/ClusterService.java`: In `delete`, call `publishResynced(empty set, old name)`. In `update`, do the same when the name changes.
6. **Registry consumer** → `infrastructure/kafka/RegistryServiceDiscoveryConsumer.java`:
   - Listen to `cartogra.ingestion.service.changed`.
   - Read the envelope as a tree and dispatch on `eventType`:
     - `service.discovered` → `upsertDiscovered`.
     - `service.removed` → `ServiceService.removeFromCluster`.
     - `service.resynced` → `ServiceService.reconcileCluster`.
     - An unknown type → log a warning and ack.
   - Add payload records in `domain/event/`.
7. **Registry repository** → `repository/ServiceRepository.java` + `infrastructure/jdbc/JdbcServiceRepository.java`: add `findByK8sCluster(tenantId, k8sCluster, updatedBefore)` → `List<Service>`. The query filters by tenant, cluster, `updated_at < :updatedBefore`, and `deleted_at IS NULL`.
8. **Registry domain** → `domain/ServiceService.java`:
   - `removeFromCluster(tenantId, cluster, ns, name)`: uses `findByK8sIdentity`. If no row matches, it does nothing.
   - `reconcileCluster(payload)`: calls `findByK8sCluster`, then sets the K8s fields to NULL for each row not in the set.
   - Both methods use one private `clearK8sFields(Service)` method. It saves the row, writes the history, writes the audit `discovery_removed` with `Actor.SYSTEM`, and calls `publishUpdated`. This is a background consumer, so a loop of saves is permitted.
9. **E2E** → `infra/helm/local-test.sh`:
   1. After the owner check (about line 313), run `kubectl -n $CHECK_NAMESPACE delete service $CHECK_SERVICE`.
   2. `wait_for "removed Service has no cluster"` until the row has no `k8sCluster`.
   3. Make sure that the row and `teamId` stay.
10. **Docs**:
    - `docs/architecture/kafka-topics.md` (topic row and event types).
    - `services/ingestion/CONTEXT.md` (lines 34, 80, 99).
    - `services/registry/CONTEXT.md`.
    - `CONTEXT-MAP.md`.
    - `seed/acme-fintech/README.md`.
    - `.claude/rules/backend.md`: the example `cartogra.ingestion.service.discovered` becomes a `.changed` example.
    - The note in ADR-0021: K8s identity, with the removal through a snapshot.
11. **Grep gate**: `grep -rn "ingestion\.service\.discovered" --exclude-dir={node_modules,bip,plans}` → no results.

## Acceptance criteria
- [ ] Ingestion sends all K8s service events on `cartogra.ingestion.service.changed`, and `event_type` ∈ `service.{discovered,removed,resynced}`.
- [ ] If a K8s Service is deleted in a tenant namespace, its row has `k8sCluster`, `k8sNamespace`, `k8sDeployment`, and `healthEndpoint` = null and health `UNKNOWN`, within seconds.
- [ ] The row, `teamId`, tier, and tags stay. A history snapshot and the audit `discovery_removed` exist. `service.updated` is sent on `cartogra.registry.service.changed`.
- [ ] If a Service is gone and the watch missed the event, the next resync removes its K8s fields.
- [ ] Production rows that are stale today lose their K8s fields after the first resync after the deploy.
- [ ] A Service created after the resync list and sent before the snapshot keeps its K8s fields (`observedAt` guard).
- [ ] If one list call fails, the resync does not send a snapshot, and no rows change.
- [ ] If the tenant label is removed from a namespace, the next resync removes the K8s fields of its rows.
- [ ] A cluster delete removes the K8s fields of all its rows. A cluster rename moves the rows to the new name after the resync of the new worker.
- [ ] A second cluster with the same name (case-insensitive) in the same tenant gives 409 `CONFLICT`.
- [ ] A Service that is created again after a removal is discovered again on the same row (claim through `k8s_cluster IS NULL`).
- [ ] The grep gate (step 11) is empty.

## Tests
- **Unit (ingestion)**:
  - `KubernetesWorker`: DELETED → `publishRemoved`. A full resync → `publishResynced` with the full set. A list exception → no snapshot. A namespace without the label is not in the set.
  - `ClusterServiceTest`: delete and rename send an empty snapshot. A duplicate name throws an exception.
  - `ServiceChangedProducer`: topic, key, and `event_type` for each method.
- **Unit (registry)** `ServiceServiceTest`:
  - `removeFromCluster` sets the fields to NULL. If no row matches, it does nothing.
  - `reconcileCluster` sets the fields to NULL only for rows not in the set and older than `observedAt`. It does not change rows in the set or newer rows.
- **IT (Testcontainers)**:
  - `KubernetesWorkerIT` and `ServiceDiscoveryFlowIT` → new topic and `event_type`.
  - `KubernetesClusterControllerIT` → 409 on a duplicate name.
  - `RegistryServiceDiscoveryConsumerIT` → discovered → removed → resynced on one topic, and the row state after each event.
  - `FlywayMigrationSmokeTest` (ingestion, if it exists) runs V006.
- **E2E**: extended `local-test.sh` on k3d `cartogra-local` (step 9). No Playwright: there is no UI change.

## Verification
1. `./gradlew :services:ingestion:build :services:registry:build`, then `./gradlew build`.
2. The grep gate (step 11).
3. Get approval for the containers, then run `sh infra/helm/local-test.sh`. Make sure that the "removed Service has no cluster" step passes.
4. Stop the k3d cluster and open the HTML report.
5. Before the production deploy, find the duplicate cluster names. If one exists, rename it before V006, or the migration fails:
   ```sql
   SELECT tenant_id, lower(name), count(*) FROM k8s_clusters WHERE deleted_at IS NULL GROUP BY 1,2 HAVING count(*) > 1;
   ```
6. After the deploy, make sure that the stale rows have no `k8s_cluster` after one resync interval (5 min).
7. Delete the old topic `cartogra.ingestion.service.discovered` (and its `.dlq` if it exists) with a one-off `kafka-topics.sh --delete`. Do this only after you verify the deploy.

## Out of scope
- The "running in cluster, no owner" view (next task).
- Rows keyed by cluster id (`k8s_cluster_id`).
- Removal of SCM repos that are deleted or archived.
- Backfill or dual-listen during the rollout window of the topic.
- A frontend change. The service page already hides the Kubernetes section when the fields are null.

## Changes during implementation
- `ClusterService` catches a Kafka failure when it sends the empty snapshot. A Kafka failure must not stop a cluster delete or update. The failure is logged, and the services keep the old cluster name.
- `frontend/src/lib/audit-format.ts`: new label for `discovery_removed` ("removed from the cluster"). Without it, the audit feed shows the raw action name.
- `ClusterRepositoryIT.count_excludesOtherTenants` used two clusters named "prod" in one tenant. V006 rejects this, so the test now uses two names.
- `infra/helm/acme/values.yaml`: Acme memory 48Mi → 64Mi. At 48Mi (commit `703f001`), the Acme pods got `OOMKilled` at start, and the k3d "Check pods" step failed. Cost: about 0.3Gi for the 21 pods.
