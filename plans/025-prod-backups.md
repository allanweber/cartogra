# Production backups + stop calling the production host "demo"
Issue: new (draft below, create on user approval) · Branch: `feat/prod-backups`
Plan file in repo: `plans/025-prod-backups.md`

## Context
The single-node k3s host at cartogra.dev is **production**. The repo calls it "demo" everywhere (`values-demo.yaml`, ADR-0030, runbooks, `global.environment: demo`, bucket `cartogra-demo-backups`, Acme `clusterName: demo`, local `~/.kube/cartogra-demo`). This misleads people and agents into judging risk to demo standards.

Found (live cluster, 2026-11-18):
- `postgres-backup` CronJob, 02:00 UTC, `pg_dump -Fc` of db `cartogra` (all schemas) → `s3://cartogra-demo-backups/postgres/`. Last 3 runs OK, 11s, 162.7 KiB.
- Bucket lifecycle: 7 days. Same Hetzner account as the host. The in-cluster key can delete objects.
- No alert on backup failure: `alerting` rules use kubelet metrics only, no kube-state-metrics.
- PVCs `data-postgres-0`, `data-kafka-0`, `data-valkey-0` on `local-path` (node disk, `reclaimPolicy: Delete`). Only storage class.
- Restore (runbook §10) tested on k3d only, never with a production dump.
- Node `cartogra-demo`, arm64, k3s v1.37.2, 4.8Gi / 7.5Gi used (≈2.7Gi free).
- `environment` label is not in any selector (`common.selectorLabels` = `app` + `app.kubernetes.io/instance`; Acme selectors = `app`) → changing it does not break `helm upgrade`.
- Acme bootstrap finds the cluster by name (`seed/acme-fintech/src/bootstrap.ts:14`). Change the name without a rename of the row = a second cluster registration. Rename the row via `PUT /api/v1/ingestion/k8s/clusters/{id}`.

## Decisions
**Rename**
- `values-demo.yaml` → `values-production.yaml`; `global.environment: production` (cartogra + acme charts).
- CI step "prod defaults" (HA `values.yaml`) → "HA defaults"; "demo values" → "production values".
- Rename live resources too (user choice): bucket → `cartogra-production-backups` (new, object-lock bucket, see backup decisions), Acme `clusterName: production`, local `~/.kube/cartogra-production`, `~/.config/cartogra-production/production.env`, wizard `infra/k8s/production-host-wizard.sh`, `demo-ca.crt` → `production-ca.crt`.
- New **ADR-0032** "Production on single-node k3s" supersedes ADR-0030 (status → Superseded). It keeps the HA exemption, names the single-node risks, and records the backup design and why Kafka/Valkey have no backup.
- **Keep** "demo" where it names the guest demo **feature** (`/demo` route, `GuestService`, `guest-demo` alert group, ADR-0013/0019, e2e `guest-demo.spec.ts`), and in historical `plans/` + `docs/bip/`.
- k8s node name `cartogra-demo` stays for now. All three `local-path` PVs (postgres, kafka, valkey) have `nodeAffinity` on hostname `cartogra-demo`, so a node rename makes Postgres unschedulable. Rename the node only after step 7 (Postgres on a Hetzner Volume), in step 10.
- Acme cluster rename needs a data fix: Registry K8s identity = (`k8s_cluster`, namespace, name) (`ServiceService.java:335`), and the claim fallback skips K8s rows with a cluster (`JdbcServiceRepository.java:277`). Without the fix, every discovery event hits the unique `(tenant_id, lower(name))` index.

**Backups**
| Gap | Decision |
|---|---|
| 1 No failure alert | Heartbeat: upload container pings healthchecks.io on success; the service emails when a ping is late (also catches a dead node or a stopped CronJob). |
| 2 Up to 24h loss | CloudNativePG + Barman Cloud plugin: continuous WAL + scheduled base backups to S3, PITR. CNPG retention 30d. |
| 3 7-day retention | Logical dump: `daily/` 30d, `monthly/` (on the 1st) 365d. |
| 4 One provider | Copy each logical dump to Cloudflare R2 too. |
| 5 Key can delete backups | New buckets with object lock (governance, 30d), keys without delete rights where the provider allows. |
| 6 Restore not proven | Weekly CronJob: restore newest dump into a throwaway Postgres, check row counts, ping a second heartbeat. |
| 7 PV on node disk | hcloud-csi; CNPG instance PVC on a Hetzner Volume. |
| 8 Keys outside git | Password manager stays the store; restore runbook gets a checklist item: the stored `CLUSTER_CREDENTIALS_KEY` decrypts restored data; stored Sealed Secrets key matches the live one. |
| — Logical dump with CNPG | Keep the `pg_dump` CronJob as the second, portable backup. |
| — Kafka/Valkey | No backup. Postgres is the source of truth; documented in ADR-0032. Restore drill confirms services recover with an empty Kafka. |
| — Delivery | One branch, linear checklist, each step on user's call. |

## Implementation steps
1. **Rename (repo only, no live change until merge)** — DONE 2026-11-18, uncommitted. The new ADR is 0032 (0031 is in use). Live effect at merge: pod label `environment=production`, Acme bootstrap looks for cluster `production` (step 8.2 first).
   - `git mv infra/helm/cartogra/values-demo.yaml infra/helm/cartogra/values-production.yaml`; `global.environment: production`; header comment.
   - `infra/helm/acme/values.yaml` → `environment: production`, `clusterName: production`; `seed/acme-fintech/src/cli.ts` default `'production'`.
   - `git mv infra/k8s/demo-host-wizard.sh infra/k8s/production-host-wizard.sh`; inside: paths, config dir, kubeconfig name, ca file.
   - `.github/workflows/deploy.yml`, `helm.yml`, `images.yml`; `infra/helm/local-test.sh`; `infra/helm/README.md`, `infra/helm/cartogra/README.md`, `infra/helm/cartogra/values.yaml`, `infra/helm/acme/Chart.yaml`, `infra/helm/acme/values.yaml`, `charts/alerting/files/rules.yml` comments.
   - `docs/runbooks/deployment.md`, `incident-response.md`, `kubernetes-cluster-config.md`, `docs/roadmap.md` (live items only), `README.md`/`CONTEXT` files where they name the host.
   - ADR-0032 + ADR-0030 status + `docs/adr/README.md` index.
   - Live renames, DONE 2026-11-18 (before the PR, because `deploy.yml` runs the Acme bootstrap at merge):
     - hcloud server, firewall, ssh-key → `cartogra-production`.
     - Local `~/.kube/cartogra-production`, `~/.config/cartogra-production/production.env` (`DEMO_IP` → `HOST_IP`); old paths are symlinks for worktrees on `main`.
     - `UPDATE registry.services SET k8s_cluster='production'` for the Acme tenant (21 rows), then `PUT /api/v1/ingestion/k8s/clusters/5ac773f8-…` `name: production`. Worker restarted, no errors, 21 services, no duplicates.
     - **Merge this PR before any other PR.** A deploy from `main` with `clusterName: demo` registers a second cluster.
   - Gate: `grep -rniI demo --exclude-dir={node_modules,.git,build,bip,plans,portfolio}` → only guest-demo feature hits.
2. **Heartbeat** — sealed secret `backup-heartbeat` (key `HEARTBEAT_URL`); upload container `curl -fsS -m 10 --retry 3 "$(cat …)"` after `aws s3 cp` succeeds, and `/fail` on error. Check that `amazon/aws-cli:2.17.0` has `curl`; else a third tiny container. healthchecks.io check: period 1d, grace 2h, email alert.
3. **New locked bucket + retention** — DONE 2026-11-18 except the scoped key and the old-bucket delete. Bucket created with object lock, governance 30d default, lifecycle daily/monthly/delete-markers; 8 dumps copied to `daily/`; a version delete fails with `forbidden by object lock`. Chart writes `daily/` + `monthly/` with `--checksum-algorithm CRC32` (object-lock PUTs need a checksum; aws-cli 2.17 sends none by default). After the merge: run a manual job, check the file in `daily/`. Governance mode can be bypassed by a key with `s3:BypassGovernanceRetention`; Hetzner keys are project-wide, so check whether the cluster key can bypass. If it can, switch to compliance mode. Local aws-cli 2.31 on Python 3.14: `list-object-versions` fails ("badly formed help string"); use `head-object` for version IDs.
   - Create `cartogra-production-backups` with object lock enabled at creation; default retention governance 30d; lifecycle `postgres/daily/` 30d, `postgres/monthly/` 365d, `cnpg/` handled by CNPG retention.
   - Check Hetzner support for object lock and for a key without delete rights. If no scoped key: record it in ADR-0032; object lock is the protection.
   - Copy the last 7 dumps from the old bucket to `daily/`. Delete the old bucket after step 4 verifies.
   - CronJob writes `daily/` and, on day 1, also `monthly/`. Values `backup.s3.bucket` → new name.
4. **Cloudflare R2 copy** — R2 bucket `cartogra-production-backups` with a bucket lock rule 30d, lifecycle 30d/365d; R2 token scoped to the bucket; sealed secret `backup-offsite-secrets`; second `aws s3 cp` with the R2 endpoint. Chart: `backup.offsite.*` values, off by default.
5. **Weekly restore verify** — `templates/backup-verify-cronjob.yaml` (Sunday 04:00): init container downloads the newest `daily/` dump; sidecar `postgres:16-alpine` on emptyDir; `pg_restore`; SQL row counts per schema (registry/topology/ingestion/gateway tables) > 0 for tables with production data; ping heartbeat 2. Resource limits 256Mi. No access to the live database.
6. **hcloud-csi** — install the `hcloud-csi` chart (pinned version, arm64 image) into `kube-system` with a sealed hcloud API token; StorageClass `hcloud-volumes` (`reclaimPolicy: Retain`). Runbook: install + rebuild steps.
7. **CloudNativePG** — operator (pinned chart, `cnpg-system`) + Barman Cloud plugin. Chart `charts/postgres` → CNPG `Cluster` (1 instance, `postgres:16` image to match, storage `hcloud-volumes` 10Gi, same `database`/owner, credentials from `postgres-secrets`), `ObjectStore` to `s3://cartogra-production-backups/cnpg/`, `ScheduledBackup` daily, retention 30d. Service name: keep `postgres` reachable (CNPG creates `<cluster>-rw`; set cluster name or add an ExternalName/alias Service so service `DB_URL`s do not change). `backup.postgres.host` → the rw service. Memory budget: operator ≤ 200Mi; check the node stays < 75%.
8. **Cutover (maintenance window, user runs or approves each command)**
   1. Run a manual backup; confirm the file in both buckets.
   2. (Done in step 1: Acme cluster row rename + data fix.)
   3. Scale apps to 0. Final `pg_dump`.
   4. Merge → `deploy.yml` installs CNPG Cluster (empty) + new values. Restore the final dump into the CNPG primary (`pg_restore --clean --if-exists`), or use CNPG `bootstrap.initdb.import` from the old service before removal — pick in this step after a k3d rehearsal.
   5. Scale apps to 1. Acme `verify` green. Trigger an on-demand CNPG `Backup`; confirm WAL files arrive.
   6. Remove the old StatefulSet + `data-postgres-0` PVC only after 7 days without problems.
   7. (Done in step 1: hcloud and local renames.) **Do not run the wizard from `main`.** It looks for `cartogra-demo` and creates a second server.
   8. Change the context name in the CI kubeconfig (GitHub `production` secret `KUBECONFIG`) only when you make that kubeconfig again. The old context name `demo` works.
9. **Node rename** (after step 8.6) — Kafka and Valkey PVs are still `local-path` on `cartogra-demo`: delete them (no backup by decision), set k3s `--node-name cartogra-production` (or the hostname), restart k3s, delete the old Node object, and let the StatefulSets make new PVCs. Short downtime.
10. **Runbooks** — `deployment.md` §10 rewritten: three backup kinds (CNPG PITR, logical dump, R2 copy), restore for each, PITR example (`bootstrap.recovery` with `targetTime`), key checklist (gap 8), heartbeat setup. `incident-response.md`: "backup heartbeat late" entry.

## Rehearsal (before step 8, on k3d `cartogra-local` via `local-test.sh`, with user approval for containers)
- Install CNPG + plugin with MinIO or a test bucket; migrate a seeded Acme database; PITR restore to a timestamp; services start and Acme verify passes.

## Acceptance criteria
- [ ] No repo text names the production host "demo" (grep gate, step 1); guest-demo feature names unchanged.
- [ ] Deployed pods carry `environment=production`; one Acme cluster row, named `production`.
- [ ] ADR-0032 accepted, ADR-0030 superseded.
- [ ] A missed or failed nightly backup sends an email within 26h (tested: suspend the CronJob for 1 day, or ping `/fail`).
- [ ] Logical dumps in Hetzner (object lock on) and R2; `daily/` 30d, `monthly/` 365d; a delete with the cluster's key fails inside the lock window.
- [ ] CNPG continuous archiving: `kubectl cnpg status` shows WAL archiving OK; a PITR restore to a chosen time works (rehearsal + one production test into a separate Cluster).
- [ ] Postgres data on a Hetzner Volume (`Retain`).
- [ ] Weekly verify job green and its heartbeat active.
- [ ] Runbook §10 covers all restore paths; key checklist done once.

## Tests
- `helm lint --strict` + `helm template` for HA defaults, production values, acme (CI `helm.yml`); kubeconform as today.
- Seed: `seed/acme-fintech/test/bootstrap.test.ts` for the default cluster name.
- e2e: none (no UI change); Acme `verify` after deploy is the end-to-end check.

## Issue draft (create only on approval)
Title: `Production backups: alerting, PITR, offsite, immutability; stop calling prod "demo"`
Body: Context + Decisions table + acceptance criteria from this plan.

## Out of scope
- HA (more nodes/replicas) for production; ADR-0032 records it as a known risk.
- Kafka/Valkey backups.
- Rename of the guest demo feature.
