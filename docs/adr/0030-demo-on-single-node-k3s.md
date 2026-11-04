# ADR-0030 — Public demo on single-node k3s, exempt from HA rules

**Date:** 2026-10-28
**Status:** Accepted
**Deciders:** Allan Weber

---

## Context

Phase 4 puts Acme Fintech behind a public URL. The roadmap said "compose on one host; Kubernetes waits for Phase 7". But Acme is not portable to compose: its catalog comes from K8s discovery and its observed edges from fake services running as pods. On compose, discovery would be frozen and Acme would need a snapshot.

`.claude/rules/infra.md` mandates `PodDisruptionBudget minAvailable: 2`, an HPA with `minReplicas ≥ 2`, and `maxUnavailable: 0` rolling updates. On one node with one replica per service these are impossible or meaningless.

## Decision

- The public demo runs on **single-node k3s** on one **ARM** VM (Hetzner CAX21: 4 vCPU, 8GB; resize to CAX31 16GB only if `kubectl top` shows pressure), deployed with the Helm charts from roadmap 7.1, which moves into Phase 4.
- Acme runs live in the same cluster (namespace `acme`); Cartogra discovers it through a read-only ServiceAccount.
- Postgres, Kafka and Valkey run in-cluster as single-replica StatefulSets.
- Images are multi-arch (`linux/amd64` + `linux/arm64`) so the demo can run on ARM, which costs about half as much per GB.
- `values-demo.yaml` sizes for 8GB (≈5GB budget):

  | Component | Demo sizing |
  |---|---|
  | k3s + Traefik + cert-manager + sealed-secrets | ~0.8GB |
  | 4 JVMs | 512Mi limit each, `-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k` |
  | Kafka (KRaft) | `KAFKA_HEAP_OPTS=-Xmx384m`, ~0.6GB |
  | Postgres | `shared_buffers=128MB`, ~0.3GB |
  | Valkey + collector + frontend | ~0.3GB |
  | 21 Acme Node pods | ~50MB each, ~1GB |

  SerialGC is a demo-only choice for low traffic; Phase 7 values keep the default collector.
- The **demo environment is exempt** from infra.md's HA rules: `values-demo.yaml` sets 1 replica and no PDB/HPA. The charts still support them through values for Phase 7.
- Not exempt: probes, resource requests + limits, pod/container securityContext, secrets as volumes (Sealed Secrets), required labels, no `latest` tags.

## Consequences

### Positive

- Acme is live: discovery, health, spans and drift all run, rather than a snapshot ageing out.
- Phase 7 starts from working charts instead of writing them cold.

### Negative / Trade-offs

- Phase 4 grows from ≈4 to ≈6 weeks.
- One node: any node failure or upgrade is downtime. Acceptable for a demo; not acceptable for paying tenants (Phase 7).
- In-cluster Postgres needs its own backup (nightly `pg_dump`); Hetzner's paid backup add-on is not used.
- CI builds every image for two architectures (`docker buildx`), which is slower.
- If 8GB proves tight, the next cut before upsizing is Acme on `pause`-image Deployments (discovery still sees 21 services) with one pod emitting spans for all of them (~1GB saved; health then reflects pause containers).

### Neutral

- Compose stays the local-dev and CI-e2e stack.

## Alternatives Considered

| Option | Reason rejected |
|--------|----------------|
| Compose on one VM + Caddy | No K8s discovery for Acme; demo data becomes a frozen or hybrid snapshot. |
| Managed Kubernetes | €50–150+/mo for a demo with no users yet. |
| x86 16GB VM (CPX41-class) | ~2–3× the cost of an ARM 8GB box for the same demo once JVMs are sized. |
| GraalVM native images | Large memory win, but Spring Boot 4 native compilation is a project of its own. |
| Redpanda instead of Kafka | Wants ~1GB per core; not lighter here. |
| Run 2 replicas to satisfy PDB/HPA on one node | Doubles RAM and buys no availability. |

## References

- `docs/roadmap.md` Phase 4 (4.5) and 7.1
- `.claude/rules/infra.md` — Kubernetes
- `plans/021-phase4-public-demo.md`
