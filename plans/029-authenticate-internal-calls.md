# Authenticate calls from the gateway to the services (security audit F1)
Branch: `docs/security-plans` (plan only)  ·  Issue: security audit F1 (`docs/security-audit/`), no GitHub issue yet

## Context
Today, the services accept the identity headers from the gateway without a check of their origin. The protection relies on the network layout only (compose network, Helm NetworkPolicy). The details are in the private audit report.

Goal: each service verifies the identity of each request, independently of the network.

Callers of the services:
- The gateway proxies all user requests (Spring Cloud Gateway routes).
- Service-to-service calls: contract → registry `/internal/services/names`, ingestion → registry `/internal/plan-limits/{id}`, topology → registry `/internal/services/access`. Some of these calls come from schedulers and have no user.
- GitHub and Azure DevOps call ingestion `/webhooks/**` through the gateway. These calls have no identity. A webhook secret authenticates them (fixed in F11).
- Tools on the host: `infra/perf/check-jvm-health.sh` reads the actuator of each service on 8081/8082/8085.

## Decisions (review these)

### D1. A signed internal token, not plain headers
- The gateway adds `X-Internal-Token` to each proxied request. The token is a JWT with `tid`, `sub` (user id), `roles`, `aud` (the target service), `iat` and `exp` (60 seconds).
- A new `InternalTokenFilter` (`shared/web`) replaces `InternalAuthFilter`. It verifies the signature, `aud` and `exp`. It makes the `Actor` and the roles from the token only.
- The filter also checks that `X-Tenant-Id` is equal to `tid`. If not, it returns 401. Thus, no change is necessary in the controllers, which read `@RequestHeader("X-Tenant-Id")`.
- A request with no token or with an incorrect token gets 401, except `/webhooks/**` and `/actuator/health/**`.

### D2. One HS256 key for all services, separate from `JWT_SECRET`
- A new secret `INTERNAL_TOKEN_KEY` (32+ bytes) goes to the gateway and to each service. The startup check is `@StrongSecret` (from F2).
- Why not ES256 (private key only in the gateway): a service that an attacker controls can then forge tokens with the shared key. But each service already has the database credentials, and all schemas use one DB user. Thus, an asymmetric key does not decrease the risk today. Select ES256 later, after plan 028 gives each service its own DB role.
- Why not mTLS: certificates for each service, rotation, and a change in each HTTP client. This costs more than the threat needs.

### D3. Service-to-service calls use a service token
- The `RestClient` of each service-to-service client gets an interceptor. The interceptor signs a token with `sub = service:<name>`, role `SERVICE`, `aud = registry` and the tenant of the call.
- `/internal/**` endpoints require role `SERVICE`. The `permitAll()` on `/internal/**` is removed.
- Then, the gateway must not proxy `/internal/**`. Add a deny route and a test.

### D4. Remove the published ports of the services
- In `docker-compose.yml`, remove `ports:` from registry, topology, contract and ingestion. The gateway reaches them on `cartogra-net`.
- `check-jvm-health.sh` then uses `docker exec <container> curl …` instead of `localhost:808x`.
- Defense in depth: D1 also protects a port that is published by mistake.

### D5. Remove the GET `permitAll`
- With D1, each request has an identity. Remove `GET/HEAD/OPTIONS permitAll` from the 4 `SecurityConfig`. Then `authenticated()` applies to all paths except `/webhooks/**`, `/actuator/health/**` and the error dispatch.
- Guest users (role `GUEST`) keep read access through the token roles.

## Steps
1. `shared/web`: `InternalTokenSigner` and `InternalTokenFilter`. Properties: `app.internal-token.key`, `ttl`. Unit tests: valid, expired, wrong key, wrong `aud`, tenant header that is not equal to `tid`.
2. Gateway: sign the token in `TenantInjectionFilter` for each route. Remove inbound `X-Internal-Token`, as the filter does today for `X-Tenant-Id`. Deny route for `/internal/**`.
3. Services: change the 4 `SecurityConfig` (D3, D5). Add the interceptor to `RegistryNameClient`, `RegistryPlanLimitClient` and `RegistryMembershipClient`.
4. Test fixtures: `InternalAuthMockMvc.withInternalAuth` and the IT helpers that send `X-User-Roles` must sign a token. Expect changes in many test files.
5. Compose (D4), `.env.example` (`INTERNAL_TOKEN_KEY`), CI secret step, `check-jvm-health.sh`.
6. Helm: add `INTERNAL_TOKEN_KEY` to the secrets of the 5 services, and update the README and NOTES. Production host: you must generate the sealed secrets again. I will write the commands.
7. Run all tests (needs Testcontainers: ask first). Run e2e and the demo flow on k3d `cartogra-local` (ask first).

## Acceptance criteria
- [ ] A direct request to a service without a valid token returns 401, for GET and for POST (an IT in each service).
- [ ] A token with a `tid` that is not equal to `X-Tenant-Id` returns 401.
- [ ] `/internal/**` without a service token returns 401. Through the gateway, it returns 404.
- [ ] `docker-compose.yml` publishes no service port except the gateway 8080.
- [ ] Webhooks, guest reads, schedulers that call registry, and e2e continue to work.

## Order with plan 028
Do this plan first. F1 is critical, and F7 is low. D2 changes to ES256 only after plan 028.
