## Summary

<!-- What does this PR do? 1-3 sentences. -->

## Changes

- 
- 

## Checklist

- [ ] Conventional commit format (`feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`)
- [ ] New domain tables have `tenant_id UUID NOT NULL`
- [ ] Flyway migration added for every schema change (no `ddl-auto`)
- [ ] REST responses use `{ data, traceId }` / `{ error, traceId }` envelope
- [ ] `X-Trace-Id` header included on all responses
- [ ] No `null` returns — `Optional<T>` or throw
- [ ] No `@Autowired` field injection — constructor injection only
- [ ] No hardcoded credentials
- [ ] Tests pass locally (`./gradlew build`)
- [ ] No CRITICAL/HIGH CVEs introduced (Trivy)
- [ ] Docs updated where relevant (ADR, OpenAPI spec, runbook)
- [ ] OTel `traceparent` propagated on all new HTTP, gRPC, and Kafka calls
