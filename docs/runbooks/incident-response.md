# Runbook: Incident Response

## Severity Levels

| Level | Definition | Response SLA | Examples |
|-------|-----------|-------------|---------|
| **P0** | Complete outage — all tenants affected | Immediate (< 5 min) | Gateway down, DB unreachable |
| **P1** | Severe degradation — core feature unavailable | 15 min | Registry writes failing, auth broken |
| **P2** | Partial degradation — non-critical feature affected | 1 hour | Intelligence service unresponsive, Kafka lag |
| **P3** | Minor — cosmetic issue or edge case | Next business day | UI rendering glitch, slow query |

---

## 1. Incident detection

Incidents are typically detected via:

- **Grafana alert** → PagerDuty → on-call engineer
- **User report** in `#incidents` Slack channel
- **Health probe failure** (`/actuator/health/live` or `/actuator/health/ready`)

---

## 2. Initial triage (all severities)

```bash
# 1. Check pod status across all namespaces
kubectl -n prod get pods --sort-by=.metadata.creationTimestamp

# 2. Look for CrashLoopBackOff / OOMKilled / Pending
kubectl -n prod describe pod <pod-name>

# 3. Check recent events
kubectl -n prod get events --sort-by=.metadata.creationTimestamp | tail -30

# 4. Check service logs (last 200 lines)
kubectl -n prod logs deployment/<service-name> --tail=200

# 5. Check HPA — is the service under-scaled?
kubectl -n prod get hpa

# 6. Check database connectivity
kubectl -n prod exec deployment/registry -- \
  curl -sf http://localhost:8081/actuator/health/ready
```

---

## 3. Service-specific diagnostics

### Gateway (auth / rate limiting)

```bash
# Redis connectivity
kubectl -n prod exec deployment/gateway -- \
  redis-cli -u $REDIS_URL ping

# JWT secret present
kubectl -n prod exec deployment/gateway -- \
  printenv JWT_SECRET_LOADED   # should print "true"

# Rate limit counters
redis-cli -u $REDIS_URL keys "tenant:*:rate_limit*" | head -20
```

### Registry (service catalog)

```bash
# DB connection pool
kubectl -n prod logs deployment/registry | grep "HikariPool"

# Flyway migration status
kubectl -n prod exec deployment/registry -- \
  curl -sf http://localhost:8081/actuator/flyway

# Check for stuck Kafka consumer
kubectl -n prod exec deployment/registry -- \
  curl -sf http://localhost:8081/actuator/health | jq '.components.kafka'
```

### Topology (dependency graph)

```bash
# Check for long-running recursive CTE queries
kubectl -n prod exec deployment/topology -- \
  curl -sf http://localhost:8082/actuator/metrics/db.query.duration

# Kafka consumer lag
kubectl -n infra exec deployment/kafka-ui -- \
  curl -sf http://localhost:8080/api/clusters/prod/consumer-groups
```

### Kafka consumer lag (any service)

```bash
# List all consumer groups with lag
kubectl -n infra exec deployment/kafka -- \
  kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 \
    --describe \
    --all-groups | grep -v "0$"
```

If lag is growing:

1. Check the consumer service logs for processing errors.
2. Verify the DLQ topic is not filling up: `cartogra.dlq.*`
3. Check for schema/deserialization errors (ClassCastException, JsonParseException).
4. If safe, restart the consumer pod: `kubectl -n prod rollout restart deployment/<service>`.

---

## 4. Escalation path

```
On-call engineer
  │
  ├── Can resolve within SLA?  → Resolve → Post-mortem (P0/P1)
  │
  └── No → Escalate to Engineering Lead
            │
            └── Data loss / security? → Escalate to VP Engineering + Legal
```

Communication channels:

- **Active incident:** `#incidents` (Slack) — post updates every 15 min for P0/P1
- **Customer-facing?** → Notify Customer Success → Update status page
- **Security incident?** → Do NOT post details in public Slack → Use `#security-incidents` (private)

---

## 5. Common remediation actions

### Restart a pod / deployment

```bash
kubectl -n prod rollout restart deployment/<service-name>
kubectl -n prod rollout status deployment/<service-name>
```

### Roll back to previous version

```bash
kubectl -n prod rollout undo deployment/<service-name>
```

See [deployment.md § 4](deployment.md#4-rolling-back-a-deployment) for full rollback procedure.

### Drain a node under memory pressure

```bash
kubectl cordon <node-name>
kubectl drain <node-name> --ignore-daemonsets --delete-emptydir-data
# After replacement node is ready:
kubectl uncordon <node-name>
```

### Force-clear a Redis rate limit key (P0 — auth locked out)

```bash
# CAUTION: only use when legitimate traffic is being blocked
redis-cli -u $REDIS_URL DEL "tenant:<tenantId>:rate_limit:<endpoint>"
```

### Replay DLQ messages

```bash
# Inspect DLQ
kubectl -n infra exec deployment/kafka -- \
  kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic cartogra.dlq.registry.service.registered \
    --from-beginning \
    --max-messages 10

# Replay: pipe DLQ messages back to the source topic
# Use the dlq-replay tool in tools/dlq-replay/ — do not use raw console-producer
./gradlew :tools:dlq-replay:run --args="--topic cartogra.registry.service.registered"
```

---

## 6. Post-mortem

A post-mortem is **required** for every P0 and P1 incident. Template: `docs/runbooks/postmortem-YYYY-MM-DD-<title>.md`

Sections:

1. **Timeline** — when detected, when resolved, key events with timestamps
2. **Root cause** — technical explanation, no blame
3. **Impact** — tenants affected, duration, data loss (if any)
4. **Detection gap** — why the alert didn't fire sooner (if applicable)
5. **Action items** — numbered, each with owner and due date

Post-mortems are shared in `#engineering-all` within 48 hours of resolution.

---

## 7. Key dashboards and links

| Resource | URL |
|----------|-----|
| Grafana — service overview | `http://grafana.internal/d/cartogra-overview` |
| Grafana — Kafka consumer lag | `http://grafana.internal/d/cartogra-kafka` |
| Jaeger — trace search | `http://jaeger.internal` |
| PagerDuty — active incidents | `https://app.pagerduty.com` |
| AWS CloudWatch — EKS logs | AWS Console → CloudWatch → Log groups → `/aws/eks/cartogra-prod` |
| Status page | `https://status.cartogra.io` |
