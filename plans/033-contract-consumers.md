# 5.4 — Consumers from the graph
Issue: #134  ·  Branch: `feat/5.4-contract-consumers`

## Context

Contract must name the services that consume a contract's service. 5.5 needs this for `affected_consumers`. The roadmap rejects a manual consumer table. Consumers come from the topology graph: services with a live inbound declared or observed edge.

Topology today publishes no edge events. Its only producers are the DLQ and the fire-and-forget audit events. No outbox exists in the repo. Registry sends inside the transaction (`ServiceLifecycleEventProducer`). Contract's consumer table is a projection: if an event is lost, a consumer stays wrong permanently. For this reason, this story adds a transactional outbox.

Facts that were found:
- `topology.dependencies`: one row per `(tenant, source, target, dependency_type, protocol)` live, `id` random UUID, soft delete by `deleted_at`. Declared and observed are separate rows.
- Edge writes: `DependencyService.create/update/delete/declareObservedPair/removeDeclaredPair`, `ObservedDependencyService.upsertEdges` (insert, or a refresh throttled to 1 min), and the `GraphNodeService.handleDeleted` cascade. No expiry job.
- Contract has no inbox table. Its idempotency comes from upserts (`JdbcServiceProjectionRepository`).
- Acme: 43 declared edges, 12 http edges into `ledger-service`.
- A 5.3 worktree is open. It also edits `local-test.sh` and the contract UI, so expect merge conflicts.

## Decisions

1. **Order**: 5.4 runs now, before 5.3 and 5.8. No code depends on them. Update the order text in the roadmap and on #134.
2. **Publish through a transactional outbox in `shared/web`**, so that it can be used again. Each service owns its `outbox` migration. The edge write and its event commit together, or neither commits.
3. **Relay**: one replica at a time, held by an advisory lock (`shared/web` `AdvisoryLockRepository`). It polls every 1 s, sends 100 rows in `id` order, and sends each row synchronously (10 s timeout). After a send, it deletes the row. On a failure, it increments `attempts`, sets `last_error`, and stops the batch. This keeps the order for each edge.
4. **Backfill**: a one-time topology Flyway **Java** migration writes one `dependency.declared|observed` outbox row for each live edge. Java reuses `EventEnvelope.of`, so the payload matches the live events.
5. **Event = one edge row**, key = edge id, topic `cartogra.topology.dependency.changed`, `event_type` `dependency.{declared,observed,removed}`. Payload `{edgeId, sourceServiceId, targetServiceId, dependencyType, protocol}`. Every edge write path sends its events. A PUT update sends `dependency.declared` again with the new values. The observed path sends only on INSERTED, not on a refresh. The service.deleted cascade sends one `removed` for each edge.
6. **Contract projection** `inbound_edges`, PK `(tenant_id, edge_id)`. `declared`/`observed` upserts the row. `removed` sets `removed_at`, and that state is final. A replay or an old event cannot bring an edge back, because topology never reuses an edge id. No inbox table. The roadmap wording changes from "idempotent on event id" to "idempotent on edge id".
7. **Consumer** = the source of a live inbound edge to the contract's service, where the protocol is **http or grpc** and the edge is declared or observed. Self edges and tombstoned services (`services.deleted_at`) are left out. All protocols are stored, so the filter can become wider later.
8. **API**: `GET /v1/contracts/{id}/consumers?limit&offset` returns a `PageResult<ConsumerResponse>` `{serviceId, serviceName|null, dependencyTypes[], protocols[]}`, sorted by name. It returns 404 when the contract is not live. 5.5 calls the same domain method.
9. **UI**: a `ContractConsumers` card below Versions on `contracts.$contractId.tsx`. It shows the count, rows that link to `/catalog/$serviceId`, type and protocol badges, an empty state, and "Show more" when there are more than 50 rows.
10. **Deviations from the rules, accepted**: `outbox.id` is a `BIGSERIAL`, because the relay needs a send order. Sent outbox rows are hard-deleted; they are transport rows, not domain rows. `inbound_edges` uses a composite PK, the same as the precedent of the contract `services` projection.

## Implementation steps

### shared/web: outbox
1. `shared/web/src/main/java/io/cartogra/web/outbox/OutboxMessage.java`: a record `(long id, UUID tenantId, String topic, String key, String payload, Map<String,String> headers)`.
2. `OutboxWriter.java`: `write(UUID tenantId, String topic, String key, String payloadJson)` inserts with `JdbcTemplate`. It adds the current W3C `traceparent` to `headers`. It throws when no transaction is active, so a write outside a transaction fails fast.
3. `OutboxRelay.java`: `relayBatch()` runs under `TenantDb.runAsSystem`. It takes the advisory lock `lockKey("outbox","relay")`, selects `ORDER BY id LIMIT :batch`, and runs `kafkaTemplate.send(ProducerRecord with headers).get(timeout)`. Then it deletes the row, or on a failure it records `attempts`/`last_error` and stops. It is not a `@Component`: each service wires it and schedules it.
4. `shared/web/build.gradle.kts`: add `compileOnly spring-tx` if it is missing. `spring-kafka` is already compileOnly.
5. Unit tests in `shared/web/src/test/.../outbox/`: see Tests.

### topology: events
6. `V016__create_outbox.sql`: the table from decision 3 (`tenant_id`, `topic`, `record_key`, `payload TEXT`, `headers JSONB`, `created_at`, `attempts`, `last_error`), ENABLE + FORCE RLS with the V015 policy shape (tenant OR `app.rls_bypass`), and grants to `cartogra_app`.
7. `src/main/java/db/migration/V017__BackfillDependencyEvents.java` (Flyway `BaseJavaMigration`): set `app.rls_bypass` for its transaction, read the live `dependencies` rows, and insert one outbox row each with `EventEnvelope.of` and a plain `JsonMapper`. Confirm that Spring Boot picks up Java migrations from `db/migration` (the default location `classpath:db/migration` covers it).
8. `domain/DependencyChangedPayload.java` record. `infrastructure/kafka/DependencyEventPublisher.java` (concrete `@Component`) has `declared(Dependency)`, `observed(...)`, and `removed(...)`, which build the envelope and call `OutboxWriter`. It has the constant `TOPIC = "cartogra.topology.dependency.changed"`.
9. `JdbcDependencyRepository` (`infrastructure/jdbc/JdbcDependencyRepository.java`): make `declareMissing` (:251), `softDeleteDeclaredPair` (:275), and `softDeleteAllForService` (:117) return the affected rows (`RETURNING`), and make `upsertObserved` (:147) return the id on INSERTED. Change the port in `repository/DependencyRepository.java` to match.
10. Call the publisher in the same transaction:
    - `DependencyService.java` `create` :66, `update` :85, `delete` :119, `declareObservedPair` :134, `removeDeclaredPair` :150
    - `ObservedDependencyService.java` `upsertEdges` :300 (INSERTED only)
    - `GraphNodeService.java` `handleDeleted` :69
    - Batch paths publish from the returned rows. Do not run a query for each edge.
11. `config/KafkaConfig.java`: add an `outboxKafkaTemplate` (String/String, `acks=all`, `enable.idempotence=true`), an `OutboxWriter` bean and an `OutboxRelay` bean, and a `NewTopic` for `cartogra.topology.dependency.changed` (the precedent is `SpanLinkConfig`).
12. `infrastructure/scheduled/OutboxRelayScheduler.java`: `@Scheduled(fixedDelayString = "${topology.outbox.relay-interval:PT1S}")` calls `relay.relayBatch()`. Put the properties in `application.yml` (`topology.outbox.batch-size: 100`, `send-timeout: PT10S`).

### contract: projection + API
13. `V006__inbound_edges.sql` (V004 and V005 were taken by 5.3 and 5.8): the table from decision 6, with `dependency_type`, `protocol`, `updated_at`, `removed_at`, and the partial index `(tenant_id, target_service_id) WHERE removed_at IS NULL`. Add RLS ENABLE + FORCE + policy + grants, the same as V003.
14. `repository/InboundEdgeRepository.java` + `infrastructure/jdbc/JdbcInboundEdgeRepository.java`:
    - `upsert(edge)`: `ON CONFLICT DO UPDATE ... WHERE removed_at IS NULL`.
    - `markRemoved(tenant, edgeId, at)`: an insert or update that sets `removed_at`, so that a `removed` that arrives first is also final.
    - `findConsumers(tenant, targetId, protocols, limit, offset)`: `GROUP BY source_service_id` with `array_agg(DISTINCT ...)`, `LEFT JOIN services` for the name, exclude `deleted_at IS NOT NULL` and self, `ORDER BY name NULLS LAST, source_service_id`. Add a count query.
15. `domain/InboundEdge.java`, `domain/Consumer.java`, and `domain/ConsumerService.java`:
    - `apply(eventType, payload)` (one service per concept).
    - `consumersOf(tenant, contractId, limit, offset)`: use `ContractRepository.findLive` (404 → `ContractNotFoundException`), then the repository query. It fills null names in **one** batch call: add `ServiceProjectionService.fillNames(tenant, Collection<UUID>)` that reuses the `RegistryNameClient.findNames` + `upsertNames` path of `fillMissingNames` (:164). The call fails open.
    - `consumersOfService(tenant, serviceId)`: for 5.5, bounded by `limit`.
16. `infrastructure/kafka/DependencyEventConsumer.java`: `@KafkaListener` on `cartogra.topology.dependency.changed`, group `${spring.kafka.consumer.group-id}`, the same structure as `ServiceEventConsumer` (traceparent extract, `EventEnvelope<JsonNode>`, `TenantContext.runAs`, switch, unknown types → ack, ack). The global DLQ handler in `config/KafkaConfig.java:40` covers `.dlq`.
17. `api/ContractController.java`: add `GET /{id}/consumers` (limit default 50, max 100, the same validation as `/versions`). Add `api/dto/ConsumerResponse.java` with `static from(Consumer)`.
18. `docs/api/contract.openapi.yaml`: add the path, `Consumer`, and `ConsumerPageEnvelope`. Add 200 and 404 cases to `ContractEnvelopeContractTest`.

### frontend
19. `src/lib/contract-types.ts`: add the `ContractConsumer` type. In `src/hooks/useContracts.ts`, add `useContractConsumers(id)` (`useInfiniteQuery`, key `['contracts', id, 'consumers']`, through `apiFetch`).
20. `src/components/ContractConsumers.tsx`: a shadcn Card, Skeleton, Alert with traceId, Badge, a Link to `/catalog/$serviceId`, an empty state, and "Show more".
21. `src/routes/_authenticated/contracts.$contractId.tsx`: render `<ContractConsumers>` below the Versions card in the left column.

### seed, k3d, docs
22. `seed/acme-fintech/src/verify.ts`: add a `verifyConsumers` check (the `ledger-service` contract lists 12 consumers) and call it from `verify()`.
23. `infra/helm/local-test.sh`: add `step "Check 5.4 contract consumers"` (see Tests).
24. Docs:
    - `docs/architecture/kafka-topics.md`: the topic is live, and fix the stale `ServiceDeletedConsumer` name and the "Topology 0 topics" entry.
    - `services/contract/CONTEXT.md`, `services/topology/CONTEXT.md`, `CONTEXT-MAP.md`, `docs/architecture/data-model.md`.
    - `.claude/rules/patterns.md`: a short "Outbox" section that says when to use it and what each service owns.
    - `docs/roadmap.md`: 5.4 `[x]`, the order text, and "idempotent on edge id".
    - The order note on #134 (a gh comment, only when the user agrees at PR time).

## Acceptance criteria
- [ ] Each edge write path (create, update, delete, drift accept, drift remove, observed insert, service.deleted cascade) writes its outbox rows in the same transaction. A rolled-back write leaves no row.
- [ ] An observed refresh (`last_seen_at` bump) writes no outbox row.
- [ ] The relay sends rows in `id` order, deletes the rows it sent, and stops at the first failure without loss of the failed row. Only one replica relays at a time.
- [ ] The backfill migration writes one outbox row for each live edge, and no rows for soft-deleted edges. Its payload deserializes to the same `EventEnvelope<DependencyChangedPayload>` as a live event.
- [ ] Contract applies declared, observed, and removed events. A replayed event changes nothing. A `declared` after `removed` for the same edge id does not bring the edge back.
- [ ] `GET /v1/contracts/{id}/consumers` lists each consumer service one time, with merged types and protocols. It includes http and grpc edges and leaves out kafka/db-only consumers, deleted services, and self edges. It returns 404 for a contract that is not live and gives a paged envelope that matches the OpenAPI file.
- [ ] One tenant's edges never show in another tenant's consumers (RLS + query).
- [ ] The contract detail page shows the Consumers card with count, links, badges, empty state, loading skeleton, and an error alert with the traceId.
- [ ] Acme: the `ledger-service` contract lists 12 consumers.
- [ ] The docs and the roadmap are updated as listed in step 24.

## Tests
- **shared/web unit**: `OutboxRelayTest` (order, delete after send, stop and record on failure, skip when the lock is not acquired) and `OutboxWriterTest` (throws without a transaction, adds the traceparent header).
- **topology IT** (Testcontainers, `AbstractTopologyIT`):
  - `DependencyEventOutboxIT`: one test for each write path, which checks the rows and payload. Also a rollback test and an observed refresh test that expects no row.
  - `OutboxRelayIT`: real Kafka. The records arrive keyed by edge id with `traceparent`, and the table drains.
  - `BackfillDependencyEventsMigrationIT`: seed edges, run the migration, and assert the rows.
  - Add `outbox` to `RowLevelSecurityIT`.
- **contract IT**:
  - `DependencyEventConsumerIT`: declared, observed, removed, replay, removed-then-declared, removed-first, unknown type ack, and poison → `.dlq`.
  - `JdbcInboundEdgeRepositoryIT`: merge, protocol filter, tombstone, self, paging, and tenant isolation.
  - `ContractControllerIT` and `ContractEnvelopeContractTest` for `/consumers` (200, 404, limit validation, guest read allowed).
  - Add `inbound_edges` to the RLS IT.
- **frontend unit**: `src/test/components/ContractConsumers.test.tsx` (rows and links, empty, loading, error with trace, show more).
- **e2e** (`frontend/e2e/contracts.spec.ts`, new test "contract consumers follow topology edges"):
  1. Through the API, create a producer service, a consumer service, and a contract, and declare an http edge.
  2. Open the detail page. The row appears (poll ≤ 15 s).
  3. Click the row, and the page goes to `/catalog/$id`. Go back.
  4. Delete the edge. The row disappears.
- **k3d** (`local-test.sh`, `step "Check 5.4 contract consumers"`, each with `pass`/`fail`):
  1. A throwaway producer, consumer, and contract plus a declared http edge: `/consumers` lists the consumer within 15 s.
  2. A grpc edge lists, and a kafka-only edge from a third service does not list.
  3. A DELETE of the http edge drops the consumer row. The grpc edge alone keeps it, so delete both and expect the consumer to be gone.
  4. The seeded `ledger-service` contract lists 12 consumers.
  5. `SELECT count(*) FROM topology.outbox` is 0 after the step.
  - Clean up through `on_exit`.

## Verification
- `./gradlew build` (all modules, the unit tests and the Testcontainers ITs).
- `cd frontend && npm run lint && npm run typecheck && npm test`.
- `infra/helm/local-test.sh --e2e` on k3d `cartogra-local`, through Monitor with live progress. Then open `build/local-test-report.html` and stop the cluster.
- Manual check on k3d: open a seeded contract (`ledger-service`). The Consumers card shows 12 rows, and the links open the catalog pages.

## Out of scope
- Endpoint-level consumer matching (6.6, #145).
- `drift.detected` (6.7, #146).
- `affected_consumers` in `POST /v1/contracts/check` (5.5). Only the domain method ships now.
- The catalog service page Contracts tab (still a placeholder).
- A move of registry's in-transaction send to the outbox.
- An expiry of observed edges (topology has none today).
