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
- **Backups** (Postgres is the only store with data that cannot be rebuilt):

  | Layer | What | Retention |
  |---|---|---|
  | Continuous | CloudNativePG with the Barman Cloud plugin: WAL archive + daily base backup to Hetzner Object Storage; point-in-time restore | 30 days |
  | Logical | Nightly `pg_dump -Fc` (the CronJob stays): Hetzner `daily/` and, on the 1st of the month, `monthly/` | 30 days daily, 365 days monthly |
  | Offsite | The same logical dump, copied to Cloudflare R2 | 30 days daily, 365 days monthly |

- The backup buckets have **object lock** (governance, 30 days). The cluster keys have no delete rights where the provider allows this.
- The Postgres volume is on a **Hetzner Volume** (hcloud-csi, `reclaimPolicy: Retain`), not on the node disk.
- **Monitoring of backups:** the backup job pings an external heartbeat (healthchecks.io) when it succeeds. A weekly job restores the newest dump into a temporary Postgres, checks the row counts, and pings a second heartbeat. A late ping sends an email.
- **Kafka and Valkey have no backup.** Valkey holds only cache and rate-limit data. If Kafka data is lost, events that were not yet consumed are lost. Services must start with an empty Kafka, and the restore drill checks this.
- `CLUSTER_CREDENTIALS_KEY` and the Sealed Secrets key stay in the password manager. A restore is not complete until the stored keys decrypt the restored data.

## Consequences

### Positive

- The maximum data loss goes from 24 hours to minutes (WAL archive).
- A failed or missing backup sends an email. A dead node also stops the ping, so it sends an email too.
- If one provider account or one cluster key is lost, the backups still exist (R2 copy, object lock).
- A node loss does not lose the database disk.

### Negative / Trade-offs

- One node: any node failure or k3s upgrade causes downtime. This is a known risk for production. HA is out of scope until there are paying tenants (Phase 7).
- The CloudNativePG operator and the CSI driver use about 300Mi of the 8GB node, and add two more components to upgrade.
- The move to CloudNativePG needs a maintenance window and a data migration.
- Three external services (healthchecks.io, Cloudflare R2, Hetzner Object Storage) must stay paid and reachable.

## Alternatives Considered

| Option | Reason rejected |
|--------|----------------|
| WAL-G on the current StatefulSet | Smaller change, but the restore is manual and there is no operator to manage base backups and retention. |
| Hourly `pg_dump` only | Up to 1 hour of data loss and no point-in-time restore. |
| kube-state-metrics + an Alertmanager rule for backup failures | The alert is in the cluster: when the node is dead, the alert is dead too. |
| Hetzner paid server backups | Snapshots of a running database disk are not consistent, and they are in the same account. |

## References

- `plans/025-prod-backups.md`
- `docs/runbooks/deployment.md` section 10
- ADR-0030 (superseded)
