# Runbook: Incident Response

Scope: the public demo, a single-node k3s host (see [deployment.md](deployment.md), ADR-0030). One person on
call, no paging. All commands assume `KUBECONFIG=~/.kube/cartogra-demo`.

## Severity

| Level | Definition | Examples |
|-------|-----------|---------|
| **P0** | Demo unreachable or leaking data | Ingress/TLS down, gateway down, a guest can write, secrets exposed |
| **P1** | Demo up but its point is broken | `/demo` fails, graph empty, Acme not discovered, Postgres full |
| **P2** | Degraded | Observed edges stale, drift flapping, SCM sync failing, backup failed |
| **P3** | Cosmetic | UI glitch, slow page |

Security (P0 data/write cases): turn guest mode off first (section 4), investigate after.

## 1. Detection

- External uptime check on `https://cartogra.dev/api/auth/features` (deployment.md section 8).
- A failed `postgres-backup` Job (`kubectl -n cartogra get jobs`).
- Someone telling you the demo is broken.

## 2. Triage

```bash
kubectl get nodes; kubectl top node                       # 8GB node: memory pressure first
kubectl -n cartogra get pods -o wide                      # CrashLoopBackOff / OOMKilled / Pending
kubectl -n cartogra get events --sort-by=.lastTimestamp | tail -30
kubectl -n cartogra describe pod <pod>                    # Last State: OOMKilled? probe failures?
kubectl -n cartogra logs deploy/<service> --tail=200      # --previous for the crashed container
kubectl -n acme-fintech get pods                          # 21 fake services
kubectl -n acme-system get jobs                           # bootstrap Job state after a deploy
```

Health from inside the cluster (actuator is not routed through the ingress):

```bash
kubectl -n cartogra exec deploy/gateway -- curl -sf localhost:8080/actuator/health/ready
kubectl -n cartogra exec deploy/registry -- curl -sf localhost:8081/api/v1/registry/actuator/health/ready
```

## 3. Known failure modes

| Symptom | Likely cause | Action |
|---|---|---|
| `OOMKilled` | `values-demo.yaml` limits too tight for real load | Raise that service's memory in `values-demo.yaml`, redeploy; resize to CAX31 only if the node itself is full |
| Pods `Pending`, PVC unbound | `local-path` disk full | `ssh` in, `df -h /var/lib/rancher`; free space or resize the volume |
| Certificate not issued | DNS not pointing at the VM, or port 80 closed | `kubectl -n cartogra describe certificate cartogra-tls`, check the HTTP-01 challenge |
| `ImagePullBackOff` | Images for that SHA not pushed, or GHCR package private | Check `images.yml` run; make the package public |
| `acme-bootstrap` Job failed | Gateway not ready within the timeout, wrong `ACME_PASSWORD`, discovery slow | `kubectl -n acme-system logs job/acme-bootstrap`; fix, then re-run **Deploy demo** (the Job is idempotent) |
| Graph has no observed edges | Collector down or Acme pods down | `kubectl -n cartogra logs deploy/collector`; `kubectl -n acme-fintech get pods` |
| Missing-drift records appear and resolve repeatedly | Acme traffic gaps longer than `TOPOLOGY_DRIFT_MISSING_AFTER` (PT1H) | Find why the fake services stopped (restarts, node pressure); never shorten `missing-after` |
| Acme services gone from the catalog | Reader token deleted/rotated or cluster disconnected | Settings → Connections → Kubernetes status; re-run **Deploy demo** |
| Guests get 429 | Per-IP mint or per-guest API bucket exhausted | Expected under abuse; see section 4 if legitimate |

### Kafka consumer lag and DLQs

```bash
kubectl -n cartogra exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --all-groups
kubectl -n cartogra exec kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list | grep '\.dlq$'
kubectl -n cartogra exec kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic <topic>.dlq --from-beginning --max-messages 5
```

Growing lag: read the consumer's logs for deserialization or processing errors before restarting it. A DLQ
record is a message the consumer gave up on; fix the cause, then let the next sync/load regenerate the data
(the seed and discovery are idempotent) rather than replaying by hand.

## 4. Remediation

```bash
kubectl -n cartogra rollout restart deploy/<service>      # restart one service
helm -n cartogra rollback cartogra <revision> --wait      # bad release (deployment.md section 9)
```

**Turn guest mode off** (suspected guest abuse or a write leak): set `gateway.env.GUEST_TENANT_ID: ""` in
`values-demo.yaml` and redeploy, or immediately:

```bash
kubectl -n cartogra patch configmap gateway-config --type merge -p '{"data":{"GUEST_TENANT_ID":""}}'
kubectl -n cartogra rollout restart deploy/gateway
```

The next `helm upgrade` restores the chart value, so make the values change too.

**Clear a rate-limit bucket** (only if legitimate traffic is blocked):

```bash
kubectl -n cartogra exec valkey-0 -- valkey-cli --scan --pattern 'rate_limit:*' | head
kubectl -n cartogra exec valkey-0 -- valkey-cli del '<key>'
```

**Reset the demo data** (Acme state corrupted): restore last night's backup (deployment.md section 10), or
for a clean slate, `helm uninstall acme -n acme-system`, delete the Acme tenant's rows, and re-run
**Deploy demo**; the gateway bootstrap and the Job recreate everything.

**Leaked secret**: rotate it (deployment.md section 4); for `JWT_SECRET` this logs everyone out, which is fine.
A leaked Sealed Secrets key means re-keying the controller and re-sealing every secret.

## 5. Afterwards

For P0/P1, write a short note in `docs/runbooks/postmortem-YYYY-MM-DD-<title>.md`: timeline, root cause,
impact, why detection was late (if it was), and follow-ups as issues.
