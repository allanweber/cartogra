# Cartogra — Topology Performance Evidence

Performance evidence for the Phase 2 gate (`docs/roadmap.md` §5, epic #119): "p95 for graph read and blast
radius recorded in `topology-performance.md` with the hardware named." Measured against a 200-edge fixture
per `docs/roadmap.md` §5, item 2.6 / GitHub issue #118, which also asks for MV refresh duration (the gate
checkbox wording omits it — issue #118's body and the roadmap story text both require it, so it's included
here regardless).

This is a **descriptive snapshot, not a pass/fail gate** — issue #118 asks to *record* these numbers, not
meet a target. No SLO threshold exists for graph read, blast radius, or MV refresh anywhere in `docs/roadmap.md`
or `docs/adr/`.

Measured 2026-10-01 (Phase 2, issue #118). Re-run `perf/bench.sh` and update this file if the topology
service's query plan, indexes, or the `dependency_graph_edges` materialized view's definition change —
these numbers will drift.

---

## Hardware

| | |
| --- | --- |
| Machine | Local dev machine (not CI, not a dedicated bench instance — see "Scope" below) |
| CPU | AMD Ryzen 5 2600 Six-Core Processor, 6 cores / 12 threads |
| Memory | 30 GiB total (9 GiB available at measurement time — other local dev processes, including Kafka/Grafana/Prometheus/the full `infra/docker-compose` observability stack, were running concurrently) |
| OS | Linux 7.0.0-34-generic, x86_64 |
| PostgreSQL | 16.15, `postgres:16-alpine` image, `infra/docker-compose/docker-compose.yml` — no CPU/memory limits set on the container, so it runs unconstrained against the host |
| Topology service | `:services:topology:bootRun`, local JVM process, not containerized |

## Fixture

`perf/graph-perf-200-seed.sql` — 130 services + 3 synthetic SPOF hub services (133 nodes total), 200
directed edges:

- 182 backbone edges: a full permutation at offset 1 over the 130 services (130 edges, one complete
  cycle) plus a partial permutation at offset 7 over the first 52 services (52 edges).
- 18 fan-in edges onto the 3 SPOF hub services (7 + 5 + 6), carried over from `seed/graph-perf-seed.sql`'s
  Phase 1 shape so this fixture isn't a bare cycle — not load-bearing for these measurements (SPOF
  detection itself is covered by issues #116/#117).

Seeded for tenant `99999999-9999-4999-8999-999999999999` directly against `registry.services` /
`topology.graph_nodes` / `topology.dependencies`, bypassing the registry API and Kafka sync. Verified via
`GET /graph`: 133 nodes, 200 edges, `truncated: false`.

## Results

| Metric | p95 / median | n | Measured via |
| --- | --- | --- | --- |
| Graph read (`GET /graph`, unfiltered, full 200-edge tenant graph) | **14.2 ms** (p95) | 60 | `perf/bench.sh`, direct HTTP against the topology service |
| Blast radius (`GET /blast-radius/{id}`, default depth 3, default direction) | **11.7 ms** (p95) | 60 distinct random starting nodes | `perf/bench.sh`, direct HTTP against the topology service |
| MV refresh (`REFRESH MATERIALIZED VIEW CONCURRENTLY dependency_graph_edges`) | **26.3 ms** (median) | 5 | `perf/bench.sh`, `psql \timing` against the fixture's fixed 200-edge dataset |

Full distribution:

| Metric | min | p50 | mean | p95 | max |
| --- | --- | --- | --- | --- | --- |
| Graph read | 6.7 ms | 9.4 ms | 10.0 ms | 14.2 ms | 20.4 ms |
| Blast radius | 5.2 ms | 7.9 ms | 8.2 ms | 11.7 ms | 13.8 ms |

| Metric | min | median | mean | max |
| --- | --- | --- | --- | --- |
| MV refresh | 24.5 ms | 26.3 ms | 27.5 ms | 33.9 ms |

Raw per-call timings: `perf/bench-results-20261001.txt`.

## Methodology

- **Tool**: `perf/bench.sh` — no benchmarking framework (JMH/Gatling/k6/etc.) exists anywhere in this repo
  yet, so this is a plain bash harness: `curl -w "%{time_total}"` against the running topology service for
  graph read and blast radius, `psql \timing` for the MV refresh statement. Not committed to CI; reusable
  for a future re-run.
- **Warm-up**: 5 discarded calls before each of graph read and blast radius, to let the JVM JIT and the
  HTTP/DB connection pools settle before sampling. Not applied to MV refresh — the `REFRESH ... CONCURRENTLY`
  statement has no JIT/connection-pool warm-up behavior to settle, and the dataset never changes between the
  5 runs, so there's nothing for an initial run to "warm."
- **Graph read**: `GET /graph` with no `teamId`/`type`/`limit` filter — returns the full 200-edge tenant
  graph in one call. p95 over 60 iterations.
- **Blast radius**: `GET /blast-radius/{serviceId}` at the default depth (3) and default direction, called
  once each against 60 *distinct* randomly-sampled service IDs from the fixture (not 60 repeats of the same
  node) — this is p95 across typical nodes, not a single node's variance. The 3 SPOF hub nodes (worst-case
  fan-in) were in the sampling pool but not singled out; a hub-specific worst case is not measured here.
- **MV refresh**: raw SQL execution time of `REFRESH MATERIALIZED VIEW CONCURRENTLY
  topology.dependency_graph_edges`, median of 5 runs. This isolates the refresh/index-rebuild cost on the
  fixed 200-edge dataset — it does **not** include `DependencyGraphViewRefreshScheduler`'s debounce window
  (`topology.graph-view.refresh-interval`, default `PT5S`), which is a fixed config value, not a query cost.
- **Requests bypass the Gateway**: called the topology service directly (`localhost:8082/api/v1/topology`)
  with just an `X-Tenant-Id` header, not through the Gateway's circuit-breaker/auth path — topology's
  `SecurityConfig` permits all requests and trusts `X-Tenant-Id` directly (normally set by the Gateway).
  Numbers reflect topology-service + Postgres cost only, not Gateway proxy overhead.

## Scope

One-time local measurement, not an automated or CI-enforced performance test. No regression gate exists on
these numbers — if they're needed again (e.g. after a schema or index change), re-run `perf/bench.sh`
against a freshly-seeded `perf/graph-perf-200-seed.sql` fixture and update this file.

Run on a local dev machine with the rest of the dev stack active in the background (Kafka, Grafana,
Prometheus, Loki, Tempo, a second unrelated Postgres container for another project), not an isolated or
dedicated benchmarking instance — treat these as representative orders of magnitude, not tightly
reproducible lab numbers.
