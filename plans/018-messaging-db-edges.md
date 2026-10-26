# 3.7 — Messaging and DB edges
Issue: #191  ·  Branch: `feat/3.7-messaging-db-edges`

## Context
Observed edges (3.1, ADR-0029) only come from HTTP/gRPC CLIENT↔SERVER spans. Kafka-only and DB dependencies never reach the graph, so those services look isolated, and blast radius, SPOF and drift miss real dependencies. 3.7 adds:
- **kafka edges**: PRODUCER and CONSUMER spans joined on `messaging.destination.name`, giving an edge from each producer to each consumer of that topic.
- **db edges**: CLIENT spans with `db.system`, only when the DB name matches a catalog service.

Constraints found:
- `pending_span_links` today is a per-call buffer: matched by span id, hard-deleted on match, 5-min TTL. A topic join is many-to-many, so it can't delete on match.
- The protocol enum and DB CHECK already allow `kafka` and `db`, and the frontend already labels KAFKA/DB, so no frontend change is needed.
- The Acme loader keys existing declared edges by `from>to` and declares everything as HTTP. Seed validation rejects a duplicate `from>to`.

## Decisions
| # | Decision | Choice | Why |
|---|---|---|---|
| 1 | Topic join storage | Reuse `pending_span_links`: add a `destination` column and `producer`/`consumer` kinds. **One row per (tenant, destination, kind, lower(service_name))**, upserted and never deleted on match | Matches the roadmap; row count is bounded by topics × services |
| 2 | Topic row lifetime | New `topology.span-links.messaging-ttl`, default `PT24H`. Upsert bumps `expires_at` only when it moves forward by more than 1 min (throttled like `last_seen_at`). The existing reaper expires idle rows | Covers infrequent producers; avoids a write per span |
| 3 | Concurrency | No advisory lock for the topic join. A producer and consumer racing on two instances can miss each other once; the next span from either side (rows live 24h) makes the edge | Self-healing; keeps the hot path lock-free |
| 4 | Messaging scope | Kafka only: PRODUCER (kind 4) / CONSUMER (kind 5) with `messaging.system=kafka` and a non-blank `messaging.destination.name`; `messaging.destination.temporary=true` skipped; topic matched exactly (case-sensitive). Other systems are skipped as `unsupported_protocol` | No enum change; Kafka is the platform's only broker |
| 5 | DB target name | First that resolves wins: `peer.service`, then `db.namespace` (falling back to legacy `db.name`), then the `server.address` host. Exact `lower(name)` match, same as today | `peer.service` is the explicit override; `db.namespace` is the logical DB name |
| 6 | Unmatched DB | No edge, no pending row, **not** recorded in `unresolved_span_services`; counter `cartogra.spans.skipped{reason=unresolved_db}` | Roadmap says "only when matched"; generic DB names would flood the 3.6 list |
| 7 | Edge direction | kafka: producer → consumer; db: client service → DB service. Self edges are skipped (existing `addEdge` rule) | Dependency flows from caller/publisher |
| 8 | Unknown own `service.name` | Unchanged: recorded as unresolved and skipped before kind dispatch | Same as 3.1/3.6 |
| 9 | Docs | Amend ADR-0029 point 6 instead of writing a new ADR; update the topology CONTEXT.md, the local-development runbook and the roadmap | Same mechanism, extended |
| 10 | Acme seed | Add a 21st service `ledger-db` (team Ledger, CRITICAL, deployed like the rest, no calls). Calls get an optional `kind: 'http' \| 'messaging' \| 'db'` (default http) and `destination` for messaging. All 3 new calls are declared. No new cycle; god service is unchanged | Exercises 1 topic with 2 consumers, a kafka edge on a pair that already has an HTTP edge, and a db edge |
| 11 | Tests | Unit + IT + Acme `verify`; no Playwright (no new UI flow) | |

New Acme calls:
```
payments-service -> notification-service  messaging  payments.completed  declared enabled
payments-service -> loyalty-service       messaging  payments.completed  declared enabled
ledger-service   -> ledger-db             db                              declared enabled
```

## Implementation steps

### Topology service (`services/topology`)
1. `src/main/resources/db/migration/V014__messaging_span_links.sql`:
   - `trace_id`, `span_id` DROP NOT NULL; ADD `destination TEXT`.
   - Drop the inline kind CHECK (`pending_span_links_kind_check`; confirm the name with `\d`) and recreate it with `client|server|producer|consumer`.
   - Add a CHECK: client/server rows need `trace_id` + `span_id`; producer/consumer rows need `destination`.
   - `CREATE UNIQUE INDEX ON pending_span_links (tenant_id, destination, kind, lower(service_name)) WHERE destination IS NOT NULL;`
2. `domain/SpanKind.java`: add `PRODUCER`, `CONSUMER`.
3. `domain/SpanObservation.java`: add `@Nullable String dbName` and `@Nullable String destination`. Update the Javadoc.
4. `infrastructure/otel/OtlpSpanMapper.java`:
   - `parseKind`: `"4"/"SPAN_KIND_PRODUCER"`, `"5"/"SPAN_KIND_CONSUMER"`.
   - Protocol: CLIENT + `db.system` → `DB` (checked before http/grpc). PRODUCER/CONSUMER + `messaging.system=kafka` + destination present + not temporary → `KAFKA`; otherwise null.
   - Fill `dbName` (`db.namespace`, falling back to `db.name`) and `destination`.
5. `repository/MessagingEndpoint.java` (new record: `destination`, `SpanKind kind`, `serviceName`). `repository/PendingSpanLinkRepository.java`: add
   - `void upsertMessagingEndpoints(UUID tenantId, Collection<MessagingEndpoint> endpoints, Instant expiresAt)`, which uses `ON CONFLICT … DO UPDATE SET expires_at = EXCLUDED.expires_at WHERE pending_span_links.expires_at < EXCLUDED.expires_at - interval '1 minute'`
   - `List<MessagingEndpoint> findMessagingEndpoints(UUID tenantId, Collection<String> destinations)`, for live rows only (`expires_at > now()`)
   - Implement both in `infrastructure/jdbc/JdbcPendingSpanLinkRepository.java`. The existing client/server queries already filter by kind; the `MAPPER` tolerates null trace/span.
6. `config/SpanLinkProperties.java`: add `@NotNull @DefaultValue("PT24H") Duration messagingTtl`. Add `messaging-ttl` to `application.yml` beside `ttl`.
7. `domain/ObservedDependencyService.ingest`: dispatch on kind after source resolution.
   - `CLIENT` with protocol `DB` → new `handleDb`: resolve `peerName`, then `dbName`, then `peerHost` via `NameResolver`. If one resolves, `addEdge`; else count `unresolved_db` (no pending row, no unknown record). Add `dbName` to `NameResolver`'s initial bulk lookup.
   - `PRODUCER`/`CONSUMER` → collect a deduped `Set<MessagingEndpoint>` for the batch.
   - After the loop, new `joinMessaging`: upsert the batch endpoints (`now + messagingTtl`), then `findMessagingEndpoints` for the batch destinations (one query). Per destination, add a KAFKA edge for every producer × consumer pair where at least one side is from this batch, resolving names via `NameResolver` and skipping self edges. Count `cartogra.spans.messaging_matched`.
   - Edges go through the existing `upsertEdges` (one `markDirty` per batch).
8. `infrastructure/scheduled/PendingSpanLinkReaperScheduler`: no change (`deleteExpired` is by `expires_at`). Verify that only.

### Acme seed (`seed/acme-fintech`)
9. `src/schema.ts`: `SeedCall.kind?: 'http' | 'messaging' | 'db'` and `destination?: string`. Validation: messaging requires `destination`; non-messaging forbids it; db/messaging cannot be external. The duplicate key becomes `from>to>kind`. Add a helper `callProtocol(c)` → `HTTP|KAFKA|DB`.
10. `seed-data.json`: add the `ledger-db` service (team Ledger, CRITICAL, unique port, stack java) and the 3 calls above.
11. `src/api.ts`: `declareDependency(source, target, protocol)`.
12. `src/load.ts`: key existing declared edges by `source>target>protocol`, and pass `callProtocol(call)`.
13. `src/manifests.ts`: the per-service env gets the HTTP calls as today (`ACME_CALLS`, http only) plus `ACME_PRODUCES` (destinations), `ACME_CONSUMES` (destinations) and `ACME_DB_CALLS` (db targets), derived from enabled calls. The consumer side of a messaging call is the call's `to`.
14. `app/tracing.ts`: add `producerSpan(destination)` (PRODUCER, `messaging.system=kafka`, `messaging.destination.name`, `messaging.operation.type=send`), `consumerSpan(destination)` (CONSUMER, `process`, no parent) and `dbSpan(target)` (CLIENT, `db.system=postgresql`, `db.namespace=target`). Spans only, no broker or DB.
15. `app/server.ts`: on each scheduler tick, also emit the producer, consumer and db spans from env. Consumers emit on their own timer, deliberately not sharing a trace with the producer, to prove the destination join.
16. `src/verify.ts`: "declared calls observed from spans" compares `from>to>protocol` using `GraphEdge.protocol`. Add explicit checks: `payments-service>notification-service KAFKA`, `payments-service>loyalty-service KAFKA`, `ledger-service>ledger-db DB` observed. `snapshot.declaredEdges` includes the protocol so the identical-state diff stays exact.
17. `README.md`: 20→21 services, 40→43 declared calls, describe call kinds, update the pod count and the verify line.

### Docs
18. `docs/adr/0029-observed-edges-from-otel-spans.md`: rewrite point 6 (messaging/DB rules, decisions 1–6); add a dated amendment note.
19. `services/topology/CONTEXT.md`: observed-edge ingestion now covers kafka/db.
20. `docs/runbooks/local-development.md` (line ~265, fixture table): supported span kinds, the messaging TTL, and new fixtures.
21. `docs/roadmap.md` 3.7: tick it, add the plan link `plans/018-messaging-db-edges.md`, and change the Acme test to ✅.

## Acceptance criteria
- [ ] A PRODUCER span (kafka, dest `T`) from service A and a CONSUMER span (dest `T`) from service B, in **separate Kafka records in either order**, create one observed edge A→B with protocol `kafka`.
- [ ] One producer and 2 consumers on the same topic give 2 kafka edges.
- [ ] Repeated spans for the same (topic, kind, service) keep exactly **one** `pending_span_links` row; `expires_at` ≈ now + 24h.
- [ ] Topic rows are not deleted on match; the reaper deletes them once expired.
- [ ] A messaging span with a non-kafka system, a missing destination or `temporary=true` creates no edge and no row.
- [ ] A CLIENT span with `db.system` whose `peer.service`, `db.namespace`/`db.name` or host matches a catalog service creates an observed `db` edge; precedence is peer.service, then db.namespace, then host.
- [ ] An unmatched DB span creates no edge, no pending row and no `unresolved_span_services` row, and increments `cartogra.spans.skipped{reason=unresolved_db}`.
- [ ] Existing HTTP/gRPC behaviour and all existing `OtelSpanWorkerIT` tests are unchanged.
- [ ] An undeclared kafka/db observed edge raises undeclared drift exactly like http (no drift code change).
- [ ] Acme: `pnpm acme verify` passes, asserting both kafka edges and the db edge; a second `load` is still a no-op.

## Tests
- **Unit**: `OtlpSpanMapperTest` covers producer/consumer kinds (numeric and string), db protocol precedence over http, `db.namespace` falling back to `db.name`, non-kafka/temporary/missing-destination → null protocol.
- **Integration**: `OtelSpanWorkerIT` with new fixtures in `src/test/resources/otel/`:
  - `producer-span.json` + `consumer-span.json`: producer first, consumer first, 2 consumers
  - row grouping (send the producer twice → 1 row)
  - non-kafka messaging skipped
  - `db-client-span.json`: matched via db.namespace, matched via peer.service, unmatched (no unresolved row)
  - The existing `messagingSpanAndSelfCallCreateNoEdge` stays valid (no destination → no edge).
- `PendingSpanLinkReaperSchedulerIT`: expired topic rows are reaped; live ones are kept.
- **Acme vitest**: `schema.test.ts` (kind/destination validation, duplicate key with kind), `manifests.test.ts` (new env vars), `load.test.ts` (protocol passed, idempotency keyed with protocol), `verify.test.ts` (kafka/db checks via `fakeApi`).
- **E2E**: none (no UI change).

## Verification
```
./gradlew :services:topology:test :services:topology:integrationTest   # or ./gradlew build
cd seed/acme-fintech && pnpm test && pnpm exec tsc --noEmit
# live: rebuild fake-service image, redeploy kind stack, then
pnpm acme load && pnpm acme verify
```
Manual check: open `/graph?type=OBSERVED` and confirm the payments→notification KAFKA edge, payments→loyalty KAFKA and ledger→ledger-db DB. In `topology.pending_span_links`, confirm 3 producer/consumer rows for `payments.completed`.

## Out of scope
- Messaging systems other than Kafka (RabbitMQ, SQS…), and a generic `messaging` protocol.
- CLIENT-kind messaging spans (`receive`/`poll` per newer semconv).
- Unmatched DB names in the unresolved list / a "create service" shortcut for DBs.
- Host suffix matching (`ledger-db.ns.svc.cluster.local` ≠ `ledger-db`).
- Cartogra's own Kafka spans (roadmap 7.9; the collector's `filter/no-self` remains).
- Topology events for new edges (6.7).
