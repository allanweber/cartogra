# Plan: Replace Jaeger with LGTM Observability Stack

## Context

Jaeger covers traces only and has no Grafana integration path in v10+. The platform already exports metrics via OTLP (`OTEL_METRICS_EXPORTER=otlp`) and has `OTEL_LOGS_EXPORTER=none` — meaning logs are silently discarded. The goal is full three-signal observability (traces + metrics + logs) with correlation in Grafana, for both local dev and production K8s, with zero Spring Boot service code changes.

**Replacement stack:**
| Signal | Backend | Version |
|--------|---------|---------|
| Traces | Grafana Tempo | 2.7.1 |
| Logs | Grafana Loki | 3.5.0 |
| Metrics | Prometheus | 3.4.0 |
| UI | Grafana | 12.0.0 |
| Pipeline | OTel Collector (unchanged) | 0.151.0 |

---

## Current State (what changes)

Two compose files exist:
- `infra/docker-compose/docker-compose.yml` — base (postgres, valkey, kafka, **jaeger**, otel-collector)
- `infra/docker-compose/docker-compose.dev.yml` — dev standalone (same + kafka-ui, valkey-ui, pgadmin)

Both files contain jaeger + otel-collector. The otel-collector config (`otel-collector.yml`) has only a single traces pipeline that exports to Jaeger.

`K8s`: `infra/k8s/` is empty (just `.gitkeep`).

---

## Files to Modify

### 1. `infra/docker-compose/docker-compose.yml`
Remove `jaeger` service entirely. Remove `otel-collector` service entirely (it cannot work without Tempo/Loki which only exist in the dev overlay). Base compose becomes pure infra: postgres + valkey + kafka only. Also remove the unused `postgres-data` volume reference inconsistency (base file has it; dev file doesn't).

### 2. `infra/docker-compose/docker-compose.dev.yml`
- Remove `jaeger` service (lines 69–83)
- Update `otel-collector` `depends_on`: remove `jaeger`, add `tempo: condition: service_healthy` and `loki: condition: service_healthy`
- Add four new services (see below) and four new named volumes
- Grafana port: use `GF_SERVER_HTTP_PORT=3001` so internal + external ports both 3001 (avoids conflict with frontend on 3000)

**New services to add:**
```
tempo:
  image: grafana/tempo:2.7.1
  command: ["-config.file=/etc/tempo-config.yml"]
  volumes: [./tempo-config.yml:/etc/tempo-config.yml:ro, tempo-data:/var/tempo]
  ports: ["3200:3200"]  # HTTP UI/API for debugging
  healthcheck: wget -qO- http://localhost:3200/ready

loki:
  image: grafana/loki:3.5.0
  command: ["-config.file=/etc/loki-config.yml"]
  volumes: [./loki-config.yml:/etc/loki-config.yml:ro, loki-data:/var/loki]
  # No host port — accessed internally by otel-collector and grafana
  healthcheck: wget -qO- http://localhost:3100/ready

prometheus:
  image: prom/prometheus:v3.4.0
  command:
    - "--config.file=/etc/prometheus/prometheus.yml"
    - "--storage.tsdb.path=/prometheus"
    - "--storage.tsdb.retention.time=7d"
    - "--web.enable-remote-write-receiver"   # required for Tempo metrics_generator
  volumes: [./prometheus.yml:/etc/prometheus/prometheus.yml:ro, prometheus-data:/prometheus]
  ports: ["9090:9090"]
  healthcheck: wget -qO- http://localhost:9090/-/healthy

grafana:
  image: grafana/grafana:12.0.0
  environment:
    GF_AUTH_ANONYMOUS_ENABLED: "true"
    GF_AUTH_ANONYMOUS_ORG_ROLE: Admin
    GF_AUTH_DISABLE_LOGIN_FORM: "true"
    GF_FEATURE_TOGGLES_ENABLE: traceqlEditor metricsSummary
    GF_SERVER_HTTP_PORT: "3001"
  volumes: [./grafana-datasources.yml:/etc/grafana/provisioning/datasources/datasources.yml:ro, grafana-data:/var/lib/grafana]
  ports: ["3001:3001"]
  depends_on: tempo (healthy), loki (healthy), prometheus (healthy)
  healthcheck: wget -qO- http://localhost:3001/api/health
```

New volumes to add: `tempo-data`, `loki-data`, `prometheus-data`, `grafana-data`

### 3. `infra/docker-compose/otel-collector.yml`
Full replacement — grows from 1 pipeline (traces→jaeger) to 3 pipelines:

```yaml
extensions:
  health_check: {}

receivers:
  otlp:
    protocols:
      grpc: { endpoint: 0.0.0.0:4317 }
      http: { endpoint: 0.0.0.0:4318 }

processors:
  batch:
    timeout: 5s
    send_batch_size: 512
  memory_limiter:
    check_interval: 1s
    limit_mib: 256
    spike_limit_mib: 64
  resource:
    attributes:
      - { action: upsert, key: service.namespace, value: cartogra }

exporters:
  otlp/tempo:
    endpoint: tempo:4317
    tls: { insecure: true }
  prometheus:
    endpoint: 0.0.0.0:8889         # Prometheus scrapes this endpoint
    namespace: cartogra
    resource_to_telemetry_conversion:
      enabled: true                 # Preserves service.name etc. as labels
  otlphttp/loki:
    endpoint: http://loki:3100/otlp # Loki 3.x OTLP endpoint; exporter appends /v1/logs

service:
  extensions: [health_check]
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, resource, batch]
      exporters: [otlp/tempo]
    metrics:
      receivers: [otlp]
      processors: [memory_limiter, resource, batch]
      exporters: [prometheus]
    logs:
      receivers: [otlp]
      processors: [memory_limiter, resource, batch]
      exporters: [otlphttp/loki]
```

Note: Port 8889 (prometheus exporter) is NOT host-exposed — Prometheus accesses it via `otel-collector:8889` on the internal network.

### 4. `.env.example` and `.env`
Single line change: `OTEL_LOGS_EXPORTER=none` → `OTEL_LOGS_EXPORTER=otlp`

Spring Boot 4 with `spring-boot-starter-opentelemetry` auto-configures a Logback→OTel bridge via `OpenTelemetryLogsBridgeAutoConfiguration`. Setting this env var activates log export to `OTEL_EXPORTER_OTLP_ENDPOINT` (already `http://localhost:4317`). The console JSON log pattern is preserved alongside OTLP export — both happen simultaneously.

### 5. `docs/runbooks/local-development.md`
- Update start command: replace `jaeger otel-collector` with `tempo loki prometheus otel-collector grafana`
- Update URLs table: remove `http://localhost:16686` (Jaeger), add:
  - `http://localhost:3001` — Grafana (traces + logs + metrics)
  - `http://localhost:9090` — Prometheus (direct query)
  - `http://localhost:3200` — Tempo HTTP API
- Update troubleshooting section: replace Jaeger-specific steps with Grafana/Loki/Tempo checks

---

## Files to Create

### 6. `infra/docker-compose/tempo-config.yml`
```yaml
stream_over_http_enabled: true
server:
  http_listen_port: 3200
  log_level: warn
distributor:
  receivers:
    otlp:
      protocols:
        grpc: { endpoint: 0.0.0.0:4317 }
        http: { endpoint: 0.0.0.0:4318 }
ingester:
  max_block_duration: 5m
compactor:
  compaction:
    block_retention: 48h
metrics_generator:
  registry:
    external_labels:
      source: tempo
      cluster: local
  storage:
    path: /var/tempo/generator/wal
    remote_write:
      - url: http://prometheus:9090/api/v1/write
        send_exemplars: true
storage:
  trace:
    backend: local
    local: { path: /var/tempo/blocks }
    wal: { path: /var/tempo/wal }
overrides:
  defaults:
    metrics_generator:
      processors: [service-graphs, span-metrics]
      generate_native_histograms: both
```

`metrics_generator` generates RED metrics from trace spans (rate, error rate, p99 duration per service pair) and remote-writes them to Prometheus. Requires `--web.enable-remote-write-receiver` on Prometheus (included in compose command).

### 7. `infra/docker-compose/loki-config.yml`
```yaml
auth_enabled: false
server:
  http_listen_port: 3100
  grpc_listen_port: 9096
  log_level: warn
common:
  instance_addr: 127.0.0.1
  path_prefix: /var/loki
  storage:
    filesystem:
      chunks_directory: /var/loki/chunks
      rules_directory: /var/loki/rules
  replication_factor: 1
  ring:
    kvstore: { store: inmemory }
query_range:
  results_cache:
    cache:
      embedded_cache:
        enabled: true
        max_size_mb: 100
schema_config:
  configs:
    - from: 2024-01-01
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h
limits_config:
  allow_structured_metadata: true   # required for traceId/spanId in structured metadata
  volume_enabled: true
  otlp_config:
    resource_attributes:
      attributes_config:
        - action: index_label
          attributes: [service.name, service.namespace]
        - action: structured_metadata
          attributes: [traceId, spanId]   # not index labels (high cardinality)
analytics:
  reporting_enabled: false
```

`traceId` goes into structured metadata (not an index label) — preserves it for correlation without high-cardinality index explosion.

### 8. `infra/docker-compose/prometheus.yml`
```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s
  external_labels:
    cluster: local
    environment: dev

scrape_configs:
  - job_name: otel-collector
    static_configs:
      - targets: ["otel-collector:8889"]  # OTel Collector prometheus exporter
  - job_name: tempo
    static_configs:
      - targets: ["tempo:3200"]           # Tempo operational metrics
  - job_name: prometheus
    static_configs:
      - targets: ["localhost:9090"]
```

No per-service scrape targets — Spring Boot services push metrics via OTLP to collector which exposes them on port 8889.

### 9. `infra/docker-compose/grafana-datasources.yml`
Three datasources provisioned automatically at Grafana startup. The `uid` values are cross-referenced to wire up correlation links:

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    uid: prometheus
    access: proxy
    url: http://prometheus:9090
    jsonData:
      timeInterval: 15s
      exemplarTraceIdDestinations:
        - name: traceID
          datasourceUid: tempo       # Exemplars → Tempo traces

  - name: Tempo
    type: tempo
    uid: tempo
    access: proxy
    url: http://tempo:3200
    jsonData:
      tracesToLogsV2:
        datasourceUid: loki
        spanStartTimeShift: "-1m"
        spanEndTimeShift: "1m"
        filterByTraceID: true        # Tempo span → Loki logs filtered by traceId
      tracesToMetrics:
        datasourceUid: prometheus
        spanStartTimeShift: "-1m"
        spanEndTimeShift: "1m"
        tags:
          - { key: service.name, value: service }
      serviceMap:
        datasourceUid: prometheus    # Service graph from Tempo metrics_generator
      nodeGraph:
        enabled: true
      lokiSearch:
        datasourceUid: loki

  - name: Loki
    type: loki
    uid: loki
    access: proxy
    url: http://loki:3100
    jsonData:
      derivedFields:
        - name: TraceID
          matcherType: label
          matcherRegex: traceId
          url: "${__value.raw}"
          datasourceUid: tempo       # Loki log line traceId → Tempo trace
          urlDisplayLabel: "View in Tempo"
```

### 10. `docs/adr/ADR-0008-lgtm-observability-stack.md`
Records the decision to replace Jaeger with the LGTM stack. Supersedes ADR-0007 §4. Key points: Grafana deprecated Jaeger datasource in v10+; OTel Collector already handles all signals; single `.env` line activates log export; Tempo `metrics_generator` produces RED metrics from traces with no service instrumentation changes.

---

## Production (K8s)

All under `infra/k8s/infra/` (the `infra` namespace, per CLAUDE.md convention).

### 11. `infra/k8s/infra/namespace.yml`
Standard namespace with labels: `app: infra`, `environment: production`, `team: platform`.

### 12–15. `infra/k8s/infra/otel-collector/` (4 files)

**`configmap.yml`**: Same 3-pipeline config as local but with K8s DNS names:
- Tempo: `tempo.infra.svc.cluster.local:4317`
- Loki gateway: `http://loki-gateway.infra.svc.cluster.local:80/otlp`
- Prometheus remote-write: `http://kube-prometheus-stack-prometheus.infra.svc.cluster.local:9090/api/v1/write`

**`deployment.yml`**: 2 replicas, all CLAUDE.md K8s rules applied:
- Resources: requests `100m/256Mi`, limits `500m/512Mi`
- All 3 probes via health check port 13133
- `securityContext`: `runAsNonRoot: true`, `runAsUser: 10001`, `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`
- `/tmp` as `emptyDir`
- Rolling update: `maxUnavailable: 0, maxSurge: 1`

**`service.yml`**: `ClusterIP` on ports 4317 (otlp-grpc), 4318 (otlp-http), 8889 (prometheus scrape). Internal address: `otel-collector.infra.svc.cluster.local`.

**`kustomization.yml`**: Lists configmap, deployment, service.

### 16–19. `infra/k8s/infra/helm/` (4 files)

All LGTM backends deployed via Helm charts (Helm repos: `grafana`, `prometheus-community`).

**`tempo-values.yml`** — `grafana/tempo` chart v1.21.1:
- Image: `grafana/tempo:2.7.1`
- Persistence: 20Gi PVC
- Resources: requests `500m/1Gi`, limits `2/2Gi`
- Config override: 720h block retention, S3 backend placeholder (local for now), `metrics_generator` with remote_write to `kube-prometheus-stack-prometheus.infra.svc.cluster.local:9090`
- `serviceMonitor.enabled: true` with label `release: kube-prometheus-stack`
- Full security context per CLAUDE.md K8s rules

**`loki-values.yml`** — `grafana/loki` chart v6.29.0:
- Image: `grafana/loki:3.5.0`
- `deploymentMode: SimpleScalable` (backend/read/write components)
- Same `otlp_config` as local (index labels: `service.name`, `service.namespace`; structured metadata: `traceId`, `spanId`)
- `gateway.enabled: true` — exposes `loki-gateway` Service for OTLP push and Grafana query
- `serviceMonitor.enabled: true`

**`kube-prometheus-stack-values.yml`** — `prometheus-community/kube-prometheus-stack` chart v72.x:
- Prometheus image `v3.4.0`, 30d retention, `enableRemoteWriteReceiver: true`
- Grafana image `12.0.0`, `additionalDataSources` wiring Tempo + Loki with same correlation config as local
- Feature toggles: `traceqlEditor metricsSummary`
- `additionalScrapeConfigs` pointing at `otel-collector.infra.svc.cluster.local:8889`
- All resources + security contexts per CLAUDE.md K8s rules
- `kubeStateMetrics.enabled: true`, `nodeExporter.enabled: true`

**`install.sh`** — Idempotent install script (documentation-as-code):
```bash
#!/usr/bin/env bash
set -euo pipefail
helm repo add grafana https://grafana.github.io/helm-charts
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
kubectl apply -f infra/k8s/infra/namespace.yml
helm upgrade --install loki grafana/loki --namespace infra --version 6.29.0 --values infra/k8s/infra/helm/loki-values.yml --wait
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace infra --version 72.0.0 \
  --values infra/k8s/infra/helm/kube-prometheus-stack-values.yml \
  --set grafana.adminPassword="${GRAFANA_ADMIN_PASSWORD}" --wait
helm upgrade --install tempo grafana/tempo --namespace infra --version 1.21.1 --values infra/k8s/infra/helm/tempo-values.yml --wait
kubectl apply -k infra/k8s/infra/otel-collector/
```

---

## Port Map (Local)

| Port | Service | Note |
|------|---------|------|
| ~~16686~~ | ~~Jaeger~~ | **Removed** |
| 3200 | Tempo HTTP | Debug/API |
| 9090 | Prometheus | Direct query |
| **3001** | **Grafana** | Main observability UI |
| 8889 | OTel Collector prometheus exporter | Internal only |
| 3100 | Loki | Internal only |

---

## Verification

### Local

```bash
# 1. Bring up stack
docker compose -f infra/docker-compose/docker-compose.dev.yml up -d
docker compose -f infra/docker-compose/docker-compose.dev.yml ps
# All services should show (healthy)

# 2. Start a service (gateway)
OTEL_LOGS_EXPORTER=otlp ./gradlew :services:gateway:bootRun &
curl http://localhost:8080/actuator/health

# 3. Verify traces in Tempo
curl "http://localhost:3200/api/search?limit=5"
# Expect: traces array with service.name=gateway

# 4. Verify logs in Loki
curl -G "http://localhost:3100/loki/api/v1/query" \
  --data-urlencode 'query={service_name="gateway"}' \
  --data-urlencode 'limit=5'
# Expect: streams with log lines

# 5. Verify metrics in Prometheus
curl "http://localhost:9090/api/v1/query?query=cartogra_jvm_memory_used_bytes"
# Expect: results with service_name label

# 6. Grafana correlation
# Open http://localhost:3001 → Explore → Tempo Search → click a trace
# → "Logs for this span" → Loki filtered by traceId ✓
# → "Metrics for this span" → RED metrics panel ✓
```

### K8s

```bash
bash infra/k8s/infra/helm/install.sh
kubectl get pods -n infra          # All Running
kubectl logs deployment/otel-collector -n infra --tail=20
kubectl port-forward svc/kube-prometheus-stack-grafana 3000:80 -n infra
# Open http://localhost:3000 — same correlation flow as local
```

---

## Implementation Order

1. `infra/docker-compose/otel-collector.yml` (new 3-pipeline config)
2. `infra/docker-compose/tempo-config.yml` (create)
3. `infra/docker-compose/loki-config.yml` (create)
4. `infra/docker-compose/prometheus.yml` (create)
5. `infra/docker-compose/grafana-datasources.yml` (create)
6. `infra/docker-compose/docker-compose.dev.yml` (remove jaeger, add 4 services + volumes)
7. `infra/docker-compose/docker-compose.yml` (remove jaeger + otel-collector entirely)
8. `.env.example` + `.env` (flip OTEL_LOGS_EXPORTER)
9. `infra/k8s/infra/namespace.yml` (create)
10. `infra/k8s/infra/otel-collector/configmap.yml` (create)
11. `infra/k8s/infra/otel-collector/deployment.yml` (create)
12. `infra/k8s/infra/otel-collector/service.yml` (create)
13. `infra/k8s/infra/otel-collector/kustomization.yml` (create)
14. `infra/k8s/infra/helm/tempo-values.yml` (create)
15. `infra/k8s/infra/helm/loki-values.yml` (create)
16. `infra/k8s/infra/helm/kube-prometheus-stack-values.yml` (create)
17. `infra/k8s/infra/helm/install.sh` (create)
18. `docs/adr/ADR-0008-lgtm-observability-stack.md` (create)
19. `docs/runbooks/local-development.md` (update URLs + troubleshooting)
