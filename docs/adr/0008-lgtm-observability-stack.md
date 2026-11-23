# ADR-0008: Replace Jaeger with LGTM Observability Stack

**Status:** Accepted  
**Date:** 2026-05-06  
**Supersedes:** ADR-0007 §4 (local observability infrastructure)

---

## Context

Jaeger covers traces only. The platform already exports metrics via OTLP (`OTEL_METRICS_EXPORTER=otlp`) but `OTEL_LOGS_EXPORTER=none` silently discards all structured log output. Grafana deprecated the Jaeger datasource in v10+, meaning trace correlation with logs and metrics has no supported path.

The OTel Collector is already in place and handles all three signal types from day one. The missing piece is a backend for each signal and a single UI to correlate them.

---

## Decision

Replace Jaeger with the LGTM stack (Loki + Grafana + Tempo + Mimir/Prometheus):

| Signal | Backend | Version |
|--------|---------|---------|
| Traces | Grafana Tempo | 2.7.1 |
| Logs | Grafana Loki | 3.5.0 |
| Metrics | Prometheus | 3.4.0 |
| UI | Grafana | 12.0.0 |
| Pipeline | OTel Collector (unchanged) | 0.151.0 |

The OTel Collector grows from one traces pipeline (→ Jaeger) to three pipelines (traces → Tempo, metrics → Prometheus exporter, logs → Loki). No Spring Boot service code changes are required.

A single environment variable change activates log export: `OTEL_LOGS_EXPORTER=none` → `OTEL_LOGS_EXPORTER=otlp`. Spring Boot 4 with `spring-boot-starter-opentelemetry` auto-configures a Logback→OTel bridge via `OpenTelemetryLogsBridgeAutoConfiguration`.

Tempo's `metrics_generator` generates RED metrics (rate, error rate, p99 duration per service pair) from trace spans and remote-writes them to Prometheus. This provides service graph data and span metrics with no additional instrumentation.

---

## Consequences

**Positive:**
- Three-signal observability (traces + metrics + logs) correlated in a single Grafana UI.
- Loki log lines carry `traceId` in structured metadata; clicking a trace in Tempo navigates directly to the correlated Loki logs.
- Prometheus exemplars from OTel Collector link metric data points to Tempo traces.
- Tempo `metrics_generator` produces RED metrics and service-graph topology automatically.
- Zero service code changes — all wiring is in the OTel Collector config and `.env`.

**Negative / Trade-offs:**
- Local dev stack grows by four containers (Tempo, Loki, Prometheus, Grafana). Memory footprint increases by ~1 GB.
- Grafana port 3001 (to avoid conflict with the frontend on 3000) — developers must update bookmarks.
- Loki `SimpleScalable` mode in K8s uses separate read/write/backend pods; more moving parts than a single-binary Jaeger deployment.

**Neutral:**
- Jaeger UI at `http://localhost:16686` is removed. All trace inspection moves to Grafana at `http://localhost:3001`.
