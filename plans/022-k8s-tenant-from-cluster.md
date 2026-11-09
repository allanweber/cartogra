# 022 — K8s worker: tenant from the cluster, not the namespace label

Issue #216. **Branch: `fix/k8s-tenant-from-cluster`.** One PR. Changes stay uncommitted until the user says to commit.

## Problem

`KubernetesWorker.handleServiceEvent` reads the tenant UUID from the namespace label `cartogra.io/tenant-id` and publishes `service.discovered` with it. `ClusterWorkerManager` builds the worker with `cluster.name()` only, so the worker never knows the owning tenant. Tenant A can label a namespace in its own cluster with tenant B's UUID and write into B's catalog.

## Decisions

| # | Decision | Why |
| --- | -------- | --- |
| D1 | Payload tenant = `cluster.tenantId()`, always | The tenant that registered the cluster is the only authenticated source |
| D2 | Label stays the opt-in switch; it counts only when its value equals the cluster's tenant. Mismatch → skip + `warn` | Keeps docs, Helm `acme` chart and seed manifests valid; a wrong or stale label never passes silently |
| D3 | Unlabelled namespaces: out of scope | Separate task ("decide on unlabelled namespaces") |

## Steps

- [x] 1. `KubernetesWorker`: constructor takes `UUID tenantId`. In `handleServiceEvent`, after the label-present check, compare the label to `tenantId`; on mismatch or invalid UUID log `warn` and return. The payload uses the field. Apply the same match check in the `resync()` filter and the namespace watch, so mismatched namespaces aren't listed.
- [x] 2. `ClusterWorkerManager.connectAndWatch`: `new KubernetesWorker(client, producer, cluster.name(), cluster.tenantId())`.
- [x] 3. Tests: update the constructors in `KubernetesWorkerIT`, `KubernetesWorkerHealthMappingTest`, `KubernetesWorkerHealthEndpointTest` and `ClusterWorkerManagerTest` (the cluster fixture's tenant must match the namespace label).
- [x] 4. New tests:
  - a namespace labelled with another tenant → producer never called
  - the published payload `tenantId` equals the cluster's tenant
- [x] 5. Docs: one line each in `docs/runbooks/kubernetes-cluster-config.md` (near the `kubectl label` command) and `services/ingestion/CONTEXT.md` §Kubernetes watch: the label value must be the tenant that registered the cluster.
- [x] 6. Verify: `./gradlew :services:ingestion:build` green. Confirm the Acme demo cluster is registered under `ACME_TENANT_ID`.

## Acceptance criteria

- The payload `tenantId` is the owning cluster's tenant, never the label value.
- A namespace labelled with another tenant publishes nothing and logs a warn.
- The existing worker tests pass, plus the 2 new tests.
- The docs state the match rule.

## Out of scope

- Registry-side validation of `service.discovered`. Ingestion is the trusted producer.
- Detecting removed Services. That is a separate task.
