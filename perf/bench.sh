#!/usr/bin/env bash
# perf/bench.sh
#
# Measures graph read p95, blast radius p95, and MV refresh duration against
# the perf/graph-perf-200-seed.sql fixture, for docs/architecture/
# topology-performance.md (docs/roadmap.md §5, item 2.6 / GitHub issue #118).
#
# Assumes the local dev stack is already up (infra/docker-compose + the
# registry/topology services running, e.g. via `./gradlew :services:topology:bootRun`)
# and that perf/graph-perf-200-seed.sql has already been applied for TENANT_ID
# (this script does not reseed, so reruns measure the same fixture).
#
# Usage:
#   TENANT_ID=<uuid> ./perf/bench.sh
#
# Env overrides (defaults match infra/docker-compose/docker-compose.yml):
#   TENANT_ID          required — the tenant the fixture was seeded under
#   TOPOLOGY_BASE_URL   default http://localhost:8082/api/v1/topology
#   PG_CONTAINER        default cartogra-postgres
#   PG_DB               default cartogra
#   PG_USER             default cartogra
#   ITERATIONS          default 60 (>= the 50 the methodology calls for, after warm-up discard)
#   WARMUP              default 5
#   MV_REFRESH_RUNS      default 5
#
# Outputs raw per-call timings (seconds) to stdout prefixed by metric name, and
# a summary block at the end. Redirect to a file to keep the raw data for the
# doc's appendix, e.g.:
#   TENANT_ID=<uuid> ./perf/bench.sh | tee perf/bench-results-$(date +%Y%m%d).txt

set -euo pipefail

TENANT_ID="${TENANT_ID:?set TENANT_ID to the tenant perf/graph-perf-200-seed.sql was run for}"
TOPOLOGY_BASE_URL="${TOPOLOGY_BASE_URL:-http://localhost:8082/api/v1/topology}"
PG_CONTAINER="${PG_CONTAINER:-cartogra-postgres}"
PG_DB="${PG_DB:-cartogra}"
PG_USER="${PG_USER:-cartogra}"
ITERATIONS="${ITERATIONS:-60}"
WARMUP="${WARMUP:-5}"
MV_REFRESH_RUNS="${MV_REFRESH_RUNS:-5}"

psql_exec() {
    docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -t -A "$@"
}

p95() {
    # stdin: one float per line. Nearest-rank p95 over sorted samples.
    sort -n | awk '
        { a[NR] = $1 }
        END {
            if (NR == 0) { print "NaN"; exit }
            idx = NR * 0.95
            idx = (idx == int(idx)) ? idx : int(idx) + 1
            if (idx > NR) idx = NR
            print a[idx]
        }'
}

echo "# bench run: $(date -u +%Y-%m-%dT%H:%M:%SZ), tenant=$TENANT_ID, iterations=$ITERATIONS, warmup=$WARMUP"

# --- Graph read ------------------------------------------------------------
# Unfiltered full-tenant graph: GET /graph with no teamId/type/limit.

echo "# warming up graph read ($WARMUP calls, discarded)"
for ((i = 0; i < WARMUP; i++)); do
    curl -s -o /dev/null -H "X-Tenant-Id: $TENANT_ID" "$TOPOLOGY_BASE_URL/graph"
done

echo "# graph_read timings (seconds), $ITERATIONS calls"
graph_read_times=$(mktemp)
for ((i = 0; i < ITERATIONS; i++)); do
    t=$(curl -s -o /dev/null -w "%{time_total}" -H "X-Tenant-Id: $TENANT_ID" "$TOPOLOGY_BASE_URL/graph")
    echo "graph_read $t"
    echo "$t" >> "$graph_read_times"
done

# --- Blast radius ------------------------------------------------------------
# p95 over ITERATIONS distinct random starting nodes, default depth (3),
# default direction. Node pool pulled fresh from the fixture each run.

echo "# sampling $ITERATIONS random service ids from the fixture"
mapfile -t node_ids < <(psql_exec -c "
    SELECT id FROM registry.services
    WHERE tenant_id = '$TENANT_ID' AND deleted_at IS NULL
    ORDER BY random() LIMIT $ITERATIONS;")

if [ "${#node_ids[@]}" -lt "$ITERATIONS" ]; then
    echo "error: fixture only has ${#node_ids[@]} services, need $ITERATIONS — reseed with a larger pool or lower ITERATIONS" >&2
    exit 1
fi

echo "# warming up blast radius ($WARMUP calls against the first sampled node, discarded)"
warm_id="${node_ids[0]}"
for ((i = 0; i < WARMUP; i++)); do
    curl -s -o /dev/null -H "X-Tenant-Id: $TENANT_ID" "$TOPOLOGY_BASE_URL/blast-radius/$warm_id"
done

echo "# blast_radius timings (seconds), ${#node_ids[@]} distinct random nodes, depth=default(3)"
blast_radius_times=$(mktemp)
for node_id in "${node_ids[@]}"; do
    t=$(curl -s -o /dev/null -w "%{time_total}" -H "X-Tenant-Id: $TENANT_ID" "$TOPOLOGY_BASE_URL/blast-radius/$node_id")
    echo "blast_radius $t"
    echo "$t" >> "$blast_radius_times"
done

# --- MV refresh --------------------------------------------------------------
# Raw REFRESH MATERIALIZED VIEW CONCURRENTLY execution time, median of
# MV_REFRESH_RUNS runs. No data changes between runs (that's intentional — this
# isolates the refresh/index-rebuild cost on a fixed 200-edge dataset, not
# write-amplification from concurrent mutation).

echo "# mv_refresh timings (seconds), $MV_REFRESH_RUNS runs"
mv_refresh_times=$(mktemp)
for ((i = 0; i < MV_REFRESH_RUNS; i++)); do
    out=$(docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c "\timing on" -c "REFRESH MATERIALIZED VIEW CONCURRENTLY topology.dependency_graph_edges;" 2>&1)
    t_ms=$(echo "$out" | grep -oE "Time: [0-9.]+ ms" | grep -oE "[0-9.]+")
    t=$(awk "BEGIN { printf \"%.4f\", $t_ms / 1000 }")
    echo "mv_refresh $t"
    echo "$t" >> "$mv_refresh_times"
done

median() {
    sort -n | awk '{ a[NR] = $1 } END { print (NR % 2 == 1) ? a[(NR+1)/2] : (a[NR/2] + a[NR/2+1]) / 2 }'
}

echo "# --- summary ---"
echo "graph_read_p95_seconds=$(p95 < "$graph_read_times")"
echo "blast_radius_p95_seconds=$(p95 < "$blast_radius_times")"
echo "mv_refresh_median_seconds=$(median < "$mv_refresh_times")"

rm -f "$graph_read_times" "$blast_radius_times" "$mv_refresh_times"
