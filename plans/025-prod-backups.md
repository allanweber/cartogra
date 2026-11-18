# Production backups + stop calling the production host "demo"
Issue: new (draft below, create on user approval) · Branches: `feat/prod-backups` (#227), `fix/backup-compliance` (#229), `feat/prod-backups-offsite`
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
| 2 Up to 24h loss | Hourly `pg_dump` (max loss 1h). CNPG + Barman (PITR) rejected for memory cost (user, 2026-11-18). |
| 3 7-day retention | Logical dump: `daily/` 30d, `monthly/` (on the 1st) 365d. |
| 4 One provider | Copy each logical dump to Cloudflare R2 too. |
| 5 Key can delete backups | New buckets with object lock (compliance, 30d). Hetzner keys are project-wide and can bypass governance. |
| 6 Restore not proven | Weekly CronJob: restore newest dump into a throwaway Postgres, check row counts, ping a second heartbeat. |
| 7 PV on node disk | Accepted (user, 2026-11-18): stays `local-path`. Node loss = new host + restore of the newest dump. hcloud-csi rejected for memory cost. |
| 8 Keys outside git | Password manager stays the store; restore runbook gets a checklist item: the stored `CLUSTER_CREDENTIALS_KEY` decrypts restored data; stored Sealed Secrets key matches the live one. |
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
2. **Heartbeat + hourly schedule** — schedule `17 * * * *` (RPO 1h; the job uses ~144Mi for ~30s). Sealed secret `backup-heartbeat` (BACKUP_URL, VERIFY_URL) from the wizard, optional volume. Upload moved to an init container; the main container `curlimages/curl:8.22.0` pings only after both uploads succeed. healthchecks.io: `cartogra-backup` period 1h, grace 30min; `cartogra-restore-verify` period 7d, grace 6h.
3. **New locked bucket + retention** — DONE 2026-11-18. Follow-up (branch `fix/backup-compliance`): aws-cli 2.17.0 `s3 cp` rejects `--checksum-algorithm`, so the nightly job failed; the chart now uses `aws-cli:2.31.35`, and a manual job wrote `postgres/daily/cartogra-2026-11-18T0932Z.dump`. The cluster key bypassed governance in a test, so the default retention is now COMPLIANCE 30d; a bypass delete then failed with `forbidden by object lock`. Old bucket `cartogra-demo-backups` deleted. Bucket created with object lock, governance 30d default, lifecycle daily/monthly/delete-markers; 8 dumps copied to `daily/`; a version delete fails with `forbidden by object lock`. Chart writes `daily/` + `monthly/` with `--checksum-algorithm CRC32` (object-lock PUTs need a checksum; aws-cli 2.17 sends none by default). After the merge: run a manual job, check the file in `daily/`. Governance mode can be bypassed by a key with `s3:BypassGovernanceRetention`; Hetzner keys are project-wide, so check whether the cluster key can bypass. If it can, switch to compliance mode. Local aws-cli 2.31 on Python 3.14: `list-object-versions` fails ("badly formed help string"); use `head-object` for version IDs.
   - Create `cartogra-production-backups` with object lock enabled at creation; default retention compliance 30d; lifecycle `postgres/daily/` 30d, `postgres/monthly/` 365d.
   - Check Hetzner support for object lock and for a key without delete rights. If no scoped key: record it in ADR-0032; object lock is the protection.
   - Copy the last 7 dumps from the old bucket to `daily/`. Delete the old bucket after step 4 verifies.
   - CronJob writes `daily/` and, on day 1, also `monthly/`. Values `backup.s3.bucket` → new name.
4. **Cloudflare R2 copy** — the user makes the R2 bucket (bucket lock 30d, lifecycle 30d/365d) and a token for that bucket only, with `infra/k8s/backup-secrets-wizard.sh`; it seals `backup-offsite-secrets` (R2_ACCESS_KEY, R2_SECRET_KEY, R2_ENDPOINT). The `upload` init container copies the dump to R2 after Hetzner. `backup.offsite.enabled: true` in production.
5. **Weekly restore verify** — `templates/backup-verify-cronjob.yaml` (Sunday 04:00): init `download` (newest `daily/` dump), init `restore` (`postgres:16-alpine` as uid 70 on an emptyDir; `pg_restore --no-owner`; `backup.verify.requiredTables` must have rows), container `heartbeat` pings `VERIFY_URL`. No access to the live database. A one-off run on 2026-11-18 passed.
6. **Memory trim** (requests were 93% of the node, real use 42%): Acme 21 × 96Mi → 64Mi + `NODE_OPTIONS=--max-old-space-size=24` (48Mi was OOMKilled at start, #232); collector 256 → 128Mi; alerting Prometheus 192 → 128Mi, Alertmanager 64 → 32Mi; Valkey 128 → 64Mi with `maxmemory 32mb`. Frees about 1Gi.
7. **CloudNativePG + Barman + hcloud-csi** — DROPPED 2026-11-18 (user: too much memory for this project). A rehearsal on production worked (import, WAL archive to the locked bucket, base backup); the Barman sidecar was OOMKilled at 128Mi; the PITR test did not finish. The operators were uninstalled; their CRDs, ClusterRole and the `postgres-app` secret were deleted the same day. Test objects under `cnpg-rehearsal/` stay locked for 30 days. Recorded as a rejected alternative in ADR-0032.
8. **Cutover** — not needed (no CNPG).
9. **Node rename** — DONE 2026-11-18, by the user's choice (no real data). Node is `cartogra-production`; the Postgres, Kafka and Valkey PVCs were deleted and made again empty; Acme was reseeded by the deploy of PR #227 (21 services, cluster `production`); old PV directories deleted from the node. Original plan: Kafka and Valkey PVs are still `local-path` on `cartogra-demo`: delete them (no backup by decision), set k3s `--node-name cartogra-production` (or the hostname), restart k3s, delete the old Node object, and let the StatefulSets make new PVCs. Short downtime.
10. **Runbooks** — `deployment.md` §10: jobs + heartbeats table, wizard, manual runs, restore from Hetzner or R2, key checklist (gap 8). `incident-response.md`: heartbeat-late detection.

## Acceptance criteria
- [x] No repo text names the production host "demo" (grep gate, step 1); guest-demo feature names unchanged.
- [x] Deployed pods carry `environment=production`; one Acme cluster row, named `production`.
- [x] ADR-0032 accepted, ADR-0030 superseded.
- [ ] A missed or failed hourly backup sends an email within 90 min (test: suspend the CronJob for 2 hours).
- [ ] Dumps in Hetzner (compliance lock) and R2 (bucket lock); `daily/` 30d, `monthly/` 365d. Hetzner: a bypass delete with the cluster key fails (DONE 2026-11-18).
- [ ] Weekly verify job green and its heartbeat active (one-off run with the rendered template passed 2026-11-18).
- [ ] Memory requests after the trim leave > 640Mi free on the node (one rollout surge).
- [ ] Runbook §10 covers the restore from Hetzner and from R2; key checklist done once.

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
