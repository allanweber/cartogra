# ADR-0032 — Production on single-node k3s; backup design

**Date:** 2026-11-18
**Status:** Accepted
**Deciders:** Allan Weber
**Supersedes:** [ADR-0030](0030-demo-on-single-node-k3s.md)

---

## Context

ADR-0030 called the single-node k3s host at cartogra.dev a "public demo" and judged its risks to demo standards. That host is **production**. The name "demo" in files, labels and resource names made people and agents accept risks that production cannot accept.

The backup in place on 2026-11-18 was one nightly `pg_dump` to one Hetzner bucket. It had a 7-day retention, no failure alert, no delete protection, no copy at a second provider, and no restore test with production data. Postgres data was on `local-path`, which is the node's own disk.

## Decision

- The host is **production**. The values file is `values-production.yaml`, the `environment` label is `production`, and live resources drop "demo" from their names (see plan 025). The guest demo **feature** (`/demo`, guest mode, ADR-0013/0019) keeps its name.
- Production stays on **one node**. It keeps ADR-0030's sizing and its exemption from the HA rules in `.claude/rules/infra.md` (1 replica, no PDB/HPA). All other rules of ADR-0030 still apply.
- **Backups** (Postgres is the only store with data that cannot be rebuilt). The design uses no permanent memory: all parts are short jobs.

  | Layer | What | Retention |
  |---|---|---|
  | Logical | Hourly `pg_dump -Fc` CronJob to Hetzner Object Storage: `daily/` each run and, on the 1st of the month, `monthly/` | 30 days daily, 365 days monthly |
  | Offsite | The same dump, copied to Cloudflare R2 in the same job | 30 days daily, 365 days monthly |

- The backup buckets have **object lock** (Hetzner: compliance, 30 days; R2: bucket lock rule, 30 days). Hetzner keys are project-wide and can bypass governance mode, so only compliance mode stops a stolen key.
- Postgres data stays on `local-path` (the node disk). If the node is lost, restore the newest dump to a new host.
- **Monitoring of backups:** the backup job pings an external heartbeat (healthchecks.io) when both uploads succeed. A weekly job restores the newest dump into a temporary Postgres, checks the row counts, and pings a second heartbeat. A late ping sends an email.
- **Kafka and Valkey have no backup.** Valkey holds only cache and rate-limit data. If Kafka data is lost, events that were not yet consumed are lost. Services must start with an empty Kafka.
- `CLUSTER_CREDENTIALS_KEY` and the Sealed Secrets key stay in the password manager. A restore is not complete until the stored keys decrypt the restored data.

## Consequences

### Positive

- The maximum data loss goes from 24 hours to 1 hour.
- A failed or missing backup sends an email. A dead node also stops the ping, so it sends an email too.
- If one provider account or one cluster key is lost, the backups still exist (R2 copy, object lock).
- Each week, a job proves that the newest dump restores.
- No operator, no CSI driver and no sidecar: nothing more to upgrade, and no permanent memory on the 8GB node.

### Negative / Trade-offs

- One node: any node failure or k3s upgrade causes downtime. This is a known risk for production. HA is out of scope until there are paying tenants (Phase 8).
- Up to 1 hour of data loss, and no point-in-time restore.
- A node loss also loses the database disk. The recovery is a new host plus a restore from the newest dump.
- Three external services (healthchecks.io, Cloudflare R2, Hetzner Object Storage) must stay paid and reachable.

## Alternatives Considered

| Option | Reason rejected |
|--------|----------------|
| CloudNativePG + Barman Cloud plugin (WAL archive, PITR) + hcloud-csi Volume | Tested on 2026-11-18: import, WAL archive to the locked bucket and base backup worked. Rejected for cost: about 670Mi of memory requests on a node with less than 1Gi free, and three more components to upgrade. Use it when the data loss of 1 hour is too much. |
| WAL-G on the current StatefulSet | PITR without an operator, but a custom image or an init container, and a manual restore. |
| kube-state-metrics + an Alertmanager rule for backup failures | The alert is in the cluster: when the node is dead, the alert is dead too. |
| Hetzner paid server backups | Snapshots of a running database disk are not consistent, and they are in the same account. |

## References

- `plans/025-prod-backups.md`
- `docs/runbooks/deployment.md` section 10
- ADR-0030 (superseded)
