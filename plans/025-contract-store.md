# 5.1 — Contract store
Issue: #131  ·  Branch: `feat/phase5-contracts` (one branch per phase)
Plan file in repo: `plans/025-contract-store.md`

## Context
Phase 5 second pillar starts here: store each service's OpenAPI contract and its immutable versions, so 5.2 (diff), 5.3 (engine), 5.8 (discovery), 5.5 (CI check) have a baseline. `services/contract` is empty (CONTEXT.md only). 5.10 already moved registry lifecycle to `cartogra.registry.service.changed`.

Found while exploring:
- Template = topology (newest). No version catalog; versions inline + `gradle.properties`. One Postgres DB `cartogra`, schema per service, own Flyway table.
- Backends set `server.servlet.context-path: /api/v1/<x>`; gateway routes path-prefix, no rewrite.
- `@PreAuthorize(Roles.CAN_WRITE)` on domain services; `InternalAuthFilter` (shared:web) builds `Actor`; controllers take `X-Tenant-Id` + `@AuthenticationPrincipal Actor`.
- Audit: `shared/web/.../audit/KafkaAuditEventAdapter` (wired per service in `config/AuditConfig`), `AuditEvent.of(...)`, `AuditKind` enum is a guest allowlist.
- Kafka: manual ack (ADR-0023), DLQ `<topic>.dlq` via `DeadLetterPublishingRecoverer` in topology `config/KafkaConfig`.
- No OpenAPI parser at runtime (swagger-parser only on test classpath via swagger-request-validator). No SHA-256 helper.
- `docs/api/contract.openapi.yaml` is a stale forward-looking design (client `version`, create without spec) — rewritten here.
- Demo node memory requests 78% (81% after PR #226); contract +512Mi → ~88%.

## Decisions
- **API**, context-path `/api/v1/contracts`:
  - `POST /` `{serviceId, spec}` → 201 contract + version 1 (one tx). Live contract exists for service → 409 `CONFLICT`, `details.contractId`. Service tombstoned → 404 `NOT_FOUND`.
  - `POST /{id}/versions` `{spec}` → 201 next version; same `spec_hash` as **latest** → 200 latest (no row, no audit); same hash as an older version (revert) → new version.
  - `GET /{id}` → contract + latest version. List + diff = 5.2.
  - One request record `ContractRequest(UUID serviceId, @NotBlank @Size(max=1_048_576) String spec)`; service enforces `serviceId` on create.
- **Errors**: invalid YAML/JSON, non-3.x (Swagger 2.0, AsyncAPI), parser validation messages → 422 `INVALID_SPEC` (new `ErrorCodes` entry), `details.messages` (≤50). Blank / >1 MiB spec → 400 `VALIDATION_ERROR`.
- **Version identity**: server `version_number` 1,2,3 per contract; `label` = spec `info.version` (display only). Numbered under `SELECT … FOR UPDATE` on the `api_contracts` row.
- **Parser**: `io.swagger.parser.v3:swagger-parser-v3` `OpenAPIV3Parser.readContents` (3.0 + 3.1); our code never imports `com.fasterxml`.
- **Canonical form**: Jackson 3 YAML/JSON → tree → recursively sorted object keys (arrays keep order) → compact JSON. `$ref` not resolved. `spec_hash` = SHA-256 hex of canonical. Store `spec_raw` + `spec_canonical`.
- **Service ref**: trust `serviceId` (no registry call). Tombstone table `deleted_services` written on `service.deleted`; create/version for tombstoned service → 404.
- **Consumer**: `cartogra.registry.service.changed`, group `contract-consumer`; only `event_type = service.deleted`, other types acked and ignored. Naturally idempotent: soft-delete `WHERE deleted_at IS NULL`, tombstone `ON CONFLICT DO NOTHING`; audit only when rows changed. No processed-events table. DLQ `cartogra.registry.service.changed.dlq` shared with topology's DLQ topic name — acceptable (envelope identical; each consumer's poison records land there).
- **Audit**: `AuditKind.CONTRACT("contract", {serviceId, versionNumber, label, specHash})`; actions `created`, `version_published`, `deleted` (SYSTEM actor). Spec content never audited.
- **`contract_checks`**: deferred to 5.5 (writer + 5.3's `changes[]` shape exist then). Roadmap text amended.
- **Demo**: `contract: <<: *jvm` (512Mi). Note node headroom ~12% → resize VM before Phase 6.
- **Acme**: v2 of `ledger-service` spec (adds a path, `info.version 1.1.0`); `repos` pushes it; `load` POSTs v1 then v2; `verify` asserts.
- **No e2e**: no UI in 5.1.

## Implementation steps

### A. Service skeleton (`services/contract`, package `io.cartogra.contract`)
1. `settings.gradle.kts` → `include("services:contract")`; root `build.gradle.kts` `debugPorts` → `contract` (5009).
2. `services/contract/build.gradle.kts` → copy topology's deps minus wiremock; add `io.swagger.parser.v3:swagger-parser-v3:<latest 2.1.x>` and `tools.jackson.dataformat:jackson-dataformat-yaml` (BOM-managed). Check Trivy after (snakeyaml).
3. `ContractApplication.java`; `src/main/resources/application.yml` from topology: port 8083, context-path `/api/v1/contracts`, `currentSchema=contract`, Flyway schema `contract`, group-id `contract-consumer`, manual_immediate ack, actuator/OTel identical, `spring.application.name: contract`.
4. `config/`: `SecurityConfig` (copy topology), `KafkaConfig` (copy: DLQ recoverer, `JacksonException` not retried), `AuditConfig` (wire `KafkaAuditEventAdapter`), `OpenTelemetryAppenderInitializer` (copy).
5. `src/main/resources/db/migration/V001__create_contract_tables.sql`:
   - `api_contracts(id, tenant_id, service_id, spec_type TEXT NOT NULL DEFAULT 'openapi' CHECK (spec_type='openapi'), created_at, updated_at, deleted_at)`; `CREATE UNIQUE INDEX … (tenant_id, service_id) WHERE deleted_at IS NULL`.
   - `contract_versions(id, tenant_id, contract_id REFERENCES api_contracts, version_number INT, label TEXT, spec_raw TEXT, spec_canonical TEXT, spec_hash CHAR(64), created_at, deleted_at)`; `UNIQUE (contract_id, version_number)`; index `(contract_id, version_number DESC) WHERE deleted_at IS NULL`.
   - `deleted_services(tenant_id, service_id, deleted_at, PRIMARY KEY (tenant_id, service_id))`.
   - RLS policy `tenant_isolation` on all three.
6. `domain/`: records `ApiContract`, `ContractVersion`, `ParsedSpec(canonical, hash, label)`; `SpecParser` (@Component: swagger-parser validate → Jackson 3 tree → canonical + SHA-256; throws `InvalidSpecException(List<String> messages)`); `ContractService` (`create`, `publishVersion`, `get`, `deleteForService`); `domain/exception/{InvalidSpecException, ContractNotFoundException, ContractAlreadyExistsException(contractId), ServiceDeletedException}`.
7. `repository/`: `ContractRepository`, `ContractVersionRepository`, `DeletedServiceRepository` interfaces; `infrastructure/jdbc/Jdbc*` impls with `NamedParameterJdbcTemplate`, every query filtered by `tenant_id`; `lockById` (`FOR UPDATE`), `findLatest`, `nextVersionNumber`, `softDeleteByService` (returns counts).
8. `api/ContractController` (only `ContractService`), `api/dto/{ContractRequest, ContractResponse.from, ContractVersionResponse.from}`, `api/GlobalExceptionHandler` (copy topology + `INVALID_SPEC` 422 with `details.messages`, `CONFLICT` 409 with `details.contractId`).
9. `infrastructure/kafka/ServiceDeletedConsumer` — pattern of topology `GraphNodeEventConsumer` (trace extract, `EventEnvelope` read, ack after service call, rethrow on error).
10. `shared/common/.../api/ErrorCodes.java` → `INVALID_SPEC`; `shared/common/.../audit/AuditKind.java` → `CONTRACT`.

### B. Plumbing
11. `infra/docker/contract/Dockerfile` ← copy topology's, s/topology/contract/, 8082→8083.
12. Gateway `services/gateway/src/main/resources/application.yml` → route `contract` (`${CONTRACT_URI:http://localhost:8083}`, `Path=/api/v1/contracts/**`, CircuitBreaker `id: contract`, `fallbackPath: /internal/fallback/contract`), `resilience4j.circuitbreaker.instances.contract` + `timelimiter.instances.contract` with `base-config: default`.
13. Helm: `infra/helm/cartogra/charts/contract/{Chart.yaml, templates/app.yaml, values.yaml}` from topology (port 8083, `probes.basePath /api/v1/contracts`, `currentSchema=contract`, `SPRING_FLYWAY_TABLE flyway_schema_history_contract`, networkPolicy allowFrom gateway); umbrella `Chart.yaml` dependency; `charts/gateway/values.yaml` `CONTRACT_URI: http://contract:8083`; `values-demo.yaml` `contract: <<: *jvm`; `charts/postgres/values.yaml` comment; `templates/NOTES.txt`, chart `README.md`; `infra/k8s/sealed/contract-secrets.yaml` (sealed, via wizard flow); `infra/k8s/demo-host-wizard.sh` (IMAGES, seal); `infra/helm/local-test.sh`.
14. Compose `infra/docker-compose/docker-compose.yml` → `contract` service (copy topology block, 8083, schema/flyway table); gateway env `CONTRACT_URI` (also add the missing `INGESTION_URI`? — no, out of scope, note only).
15. OTel collector `filter/no-self` → `contract` in `infra/docker-compose/otel-collector.yml` and `infra/helm/cartogra/charts/collector/files/otel-collector.yml`.
16. CI: `.github/workflows/ci.yml` uncomment contract build (lines ~170) + report/coverage upload steps like topology + summary row; `ci-full.yml` uncomment (~77); `images.yml` matrix entry; `deploy.yml` image loop.

### C. Acme (`seed/acme-fintech`)
17. `src/repos.ts` `openapi()` → v2 variant for `ledger-service` (extra path, `info.version: 1.1.0`) behind a seed-data field; keep v1 renderer exported for `load`.
18. `src/api.ts` → `createContract`, `publishVersion`, `getContract`.
19. `src/load.ts` → for ledger-service: create with v1 (on 409 use `details.contractId`), then publish v2.
20. `src/verify.ts` → checks: contract exists, latest `versionNumber ≥ 2`, v1/v2 hashes differ, re-POST v2 returns 200 same version.
21. `test/*.test.ts` + `test/fakeApi.ts` → cover the above.

### D. Frontend
22. `frontend/src/lib/audit-format.ts` → labels for `contract` `created`/`version_published`/`deleted` (+ its unit test).

### E. Docs
23. `docs/api/contract.openapi.yaml` → rewrite to the 3 implemented endpoints, envelope, error codes; drop "not yet implemented".
24. `services/contract/CONTEXT.md` (status, tables, endpoints, tombstone), `CONTEXT-MAP.md` status row, `README.md:161`, `docs/architecture/data-model.md` (contract tables as built; checks → 5.5), `docs/architecture/kafka-topics.md` (contract consumer row + DLQ), `docs/roadmap.md` (5.1 drops `contract_checks`, 5.5 gains it; tick 5.1 at the end), postman env already has `contract-url`.

## Acceptance criteria
- [ ] `POST /api/v1/contracts {serviceId, spec}` via gateway → 201, contract + version 1 with `specHash` (64 hex) and `label` = `info.version`.
- [ ] Same spec in YAML and JSON, with reordered keys/comments → identical `specHash`.
- [ ] Second create for the same service → 409 `CONFLICT`, `details.contractId` = existing id.
- [ ] `POST /{id}/versions` with a changed spec → 201 `versionNumber = n+1`; with the latest's spec → 200, no new row, no audit event; with an older version's spec → new version.
- [ ] Concurrent publishes on one contract never produce duplicate `versionNumber`.
- [ ] Invalid YAML, Swagger 2.0 doc, OpenAPI missing `info`/`paths` → 422 `INVALID_SPEC` with non-empty `details.messages`; blank or >1 MiB spec → 400 `VALIDATION_ERROR`. All responses enveloped with `traceId` = `X-Trace-Id`.
- [ ] `GET /{id}` returns contract + latest version; other tenant's id → 404.
- [ ] GUEST and VIEWER get 403 on every contract write (service `WriteAuthorizationIT` + gateway `GuestModeIT`).
- [ ] Each create / published version / cascade delete emits one `cartogra.platform.audit.recorded` with `entityType=contract`, and registry's Timeline shows it.
- [ ] `service.deleted` on `cartogra.registry.service.changed` soft-deletes the contract + versions and tombstones the service; replay changes nothing and emits no second audit; later create/version for that service → 404. Poison record → `.dlq`.
- [ ] Gateway `contract` route has a same-named breaker; open breaker → 503 `SERVICE_UNAVAILABLE`.
- [ ] `./gradlew build` green incl. contract; ci.yml/ci-full.yml run contract; `helm lint --strict` + template (prod + demo) pass; compose `contract` healthy.
- [ ] Acme: `repos` pushes ledger-service v2; `load` re-runnable; `verify` passes contract checks locally and on demo after deploy.

## Tests
- **Unit**: `SpecParserTest` (YAML≡JSON hash, key-order invariance, arrays order-sensitive, 3.0 + 3.1 accepted, 2.0/garbage/missing info rejected with messages, label extraction); `ContractServiceTest` (Mockito: 409 path, same-hash no-op, revert creates version, tombstone 404, audit calls per action).
- **IT** (Testcontainers, `AbstractContractIT` like `AbstractTopologyIT`): `ContractControllerIT` (HTTP, all AC paths incl. concurrent publish with 2 virtual threads); `ServiceDeletedConsumerIT` (soft-delete, tombstone, replay idempotent, ignore other event types, DLQ); `WriteAuthorizationIT` (via `WriteRoutes`, sanity route `POST /`); `ContractEnvelopeContractTest` (validates responses against `docs/api/contract.openapi.yaml` with `OpenApiContractValidator`); audit IT asserting the Kafka record.
- **Gateway**: `ContractCircuitBreakerConfigTest` (copy `IngestionCircuitBreakerConfigTest`); `GuestModeIT` `PROXIED_WRITE_PATHS` += `/api/v1/contracts`, `/api/v1/contracts/{uuid}/versions`.
- **Frontend**: audit-format unit test.
- **Acme**: vitest for load/verify contract steps.
- **E2E**: none (no UI until 5.2).

## Verification
```bash
./gradlew :services:contract:build :services:gateway:build :shared:common:build
./gradlew build
cd frontend && pnpm test
cd seed/acme-fintech && pnpm test
helm lint --strict infra/helm/cartogra && helm template t infra/helm/cartogra -f infra/helm/cartogra/values-demo.yaml --set alerting.email.to=x@example.invalid >/dev/null
docker compose -f infra/docker-compose/docker-compose.yml up -d --build contract gateway
```
Manual (local stack): POST a spec via gateway (:8080), re-POST same (200), POST v2 (201), POST Swagger 2.0 (422); delete the service in registry → contract GET 404; Timeline shows contract entries. After merge + demo deploy: `pnpm acme load && pnpm acme verify` against cartogra.dev (memory: reference_demo_acme_verify); check node memory requests.

## Out of scope
- `GET /contracts` list, `/versions/{v}/diff`, contracts UI, dropping `MOCK_CONTRACTS` (5.2).
- Breaking-change engine (5.3); `contract_checks` table, API keys, `/check` (5.5); spec discovery + ADR-0018 key amendment (5.8); consumer projection (5.4); Slack (5.7).
- AsyncAPI; validating `serviceId` against registry; `DELETE /contracts/{id}` endpoint.
- Compose gateway's missing `INGESTION_URI` (pre-existing gap; note only).
