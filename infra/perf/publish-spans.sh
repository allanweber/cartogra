#!/usr/bin/env bash
# Publishes SPAN_COUNT OTLP-JSON CLIENT spans for TENANT_ID straight onto the span topic, bypassing
# the collector (the production compose stack has none). Edges run between the perf-svc-N services
# the k6 setup registers; every fifth targets an unregistered ghost-N name to grow unresolved state.
set -euo pipefail

: "${TENANT_ID:?TENANT_ID is required (see login.sh)}"
SPAN_COUNT=${SPAN_COUNT:-20000}
SERVICE_COUNT=${SERVICE_COUNT:-8}
KAFKA_CONTAINER=${KAFKA_CONTAINER:-cartogra-kafka}
TOPIC=${TOPIC:-cartogra.observability.spans}

awk -v n="$SPAN_COUNT" -v s="$SERVICE_COUNT" -v tenant="$TENANT_ID" 'BEGIN {
  srand()
  for (i = 0; i < n; i++) {
    source = "perf-svc-" (i % s)
    target = (i % 5 == 0) ? "ghost-" (i % 50) : "perf-svc-" ((i + 1) % s)
    trace = sprintf("%08x%08x%08x%08x", rand() * 4294967295, rand() * 4294967295, i, rand() * 4294967295)
    span = sprintf("%08x%08x", i, rand() * 4294967295)
    printf "{\"resourceSpans\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"%s\"}},{\"key\":\"cartogra.tenant.id\",\"value\":{\"stringValue\":\"%s\"}}]},\"scopeSpans\":[{\"spans\":[{\"traceId\":\"%s\",\"spanId\":\"%s\",\"name\":\"GET /perf\",\"kind\":3,\"attributes\":[{\"key\":\"http.request.method\",\"value\":{\"stringValue\":\"GET\"}},{\"key\":\"peer.service\",\"value\":{\"stringValue\":\"%s\"}}]}]}]}]}\n", source, tenant, trace, span, target
  }
}' | docker exec -i "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic "$TOPIC" >/dev/null

echo "Published ${SPAN_COUNT} spans to ${TOPIC} for tenant ${TENANT_ID}"
