# 3.5 — Topology events (deferral close)
Issue: #123 (follow-up: #146)  ·  Branch: `feat/3.5-topology-events-deferral`

## Context
Roadmap 3.5 publishes `dependency.declared/observed/removed` + `drift.detected` only if a consumer exists. Consumer = Intelligence (Phase 6), which is an empty dir (`services/intelligence/CONTEXT.md` only). Rule 4 (no topic without a consumer) → nothing to publish. #123 exists to record the deferral; close by confirming no such topic was introduced. Verified: no `cartogra.topology.*` topic, no producer class in topology.
Leftover found: `services/topology/src/main/java/io/cartogra/topology/config/KafkaConfig.java` defines `producerFactory` (`ProducerFactory<String, Object>`, JSON serializer) + `kafkaTemplate` (`KafkaTemplate<String, Object>`) that nothing injects. Producer scaffolding with no producer → remove.

## Decisions
- No topic, no producer, no event records — rule 4.
- Remove unused `producerFactory` + `kafkaTemplate` beans; keep `dlqProducerFactory`, `dlqKafkaTemplate`, `kafkaErrorHandler`. 6.7 re-adds what it needs.
  - Side effect: `dlqKafkaTemplate` becomes sole `KafkaTemplate` → Boot listener factory may use it as reply template. Harmless: no `@SendTo` listeners. Boot autoconfig stays off (`@ConditionalOnMissingBean` satisfied by DLQ beans).
- Docs: tick roadmap 3.5 as closed-by-deferral. `docs/architecture/kafka-topics.md`, `services/topology/CONTEXT.md`, `CONTEXT-MAP.md` already state the deferral → unchanged.
- Issues: close #123 with evidence comment; fix #146 body "Deferred from story 3.4" → "3.5". Only after merge, with user go-ahead.
- Commit type `chore(topology):`; branch follows `feat/3.N-` story pattern.

## Implementation steps
1. `services/topology/src/main/java/io/cartogra/topology/config/KafkaConfig.java` → delete `producerFactory` and `kafkaTemplate` bean methods; drop now-unused imports (`JacksonJsonSerializer`, `ObjectMapper`, `JsonMapper`). Javadoc on `dlqProducerFactory` still accurate.
2. `docs/roadmap.md:207` → `[x]`, append: "Closed by deferral: no consumer exists, so no topic or producer was introduced in Phase 3; work moves to 6.7. Unused topology JSON `KafkaTemplate` removed."
3. Post-merge (user go-ahead): `gh issue close 123 --comment "<evidence: grep shows no cartogra.topology.* topic/producer; intelligence service empty; tracked in #146>"`; `gh issue edit 146` body 3.4 → 3.5.

## Acceptance criteria
- [ ] `grep -rn "cartogra.topology\." services/*/src/main` returns no topic declarations/sends.
- [ ] Topology `KafkaConfig` has no `ProducerFactory<String, Object>` / `KafkaTemplate<String, Object>` bean.
- [ ] Topology app context loads; all topology tests + ITs green (DLQ behaviour in `OtelSpanWorkerIT` / `GraphNodeEventConsumerIT` unchanged).
- [ ] Roadmap 3.5 ticked with deferral note; Phase 3 gate line "No new Kafka topic exists without a consumer" holds.
- [ ] #123 closed with evidence; #146 body references 3.5.

## Tests
- Unit/integration: none new. Existing topology ITs cover context load + DLQ publishing.
- E2E: n/a (no user-facing change). Acme: n/a.

## Verification
- `./gradlew :services:topology:build` (incl. ITs) then `./gradlew build`.
- `grep -rn "KafkaTemplate<String, Object>\|cartogra.topology\." services/topology/src/main` → empty.
- `/code-review` + `/security-review` on diff (story DoD).

## Out of scope
- Publishing topology events (6.7, #146).
- Any Intelligence service work.
- Similar unused-producer audits in other services.
