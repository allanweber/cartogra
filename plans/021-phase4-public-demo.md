# 021 — Phase 4: a product a stranger can try

Gate #130. Source: `/grill-with-docs` session 2026-10-27/28. Roadmap `docs/roadmap.md` Phase 4 (now ≈6 weeks, 7.1 pulled in).

**Branch: `docs/phase-4-plan` (all steps).** Run one step at a time when called; changes stay uncommitted until the user says to commit. Build green (`./gradlew build`, frontend tests) before the next step. Rebase on main between steps if main moves.

Order: app code first (testable on compose) → charts → deploy → ops → gate.

## Checklist

- [x] 1. Collector in compose (#192)
- [x] 2. Audit trail (#125)
- [x] 3. Dashboard on real data (#126)
- [x] 4. Guest mode + signup off (#128, signup part of #129)
- [x] 5. Helm charts (#148)
- [x] 6. Deploy pipeline + in-cluster Acme (#129, code)
- [ ] 7. Ops — public host (#129)
- [ ] 8. Gate (#130)

## 1. Collector in compose (#192)

As written (compose collector; `demo-flow` observed-edge step always runs, `E2E_OBSERVED` gate removed). Plus a demo collector config without Tempo/Loki/Prometheus exporters, consumed by the Helm chart. Collector is never exposed via ingress.

Done when: `pnpm acme verify` observed-edge check passes against compose.

## 2. Audit trail (#125)

ADR-0017 → Accepted, topic `cartogra.platform.audit.recorded`.

- Registry owns `audit_events`: `tenant_id`, `entity_type`, `entity_id`, `action`, `actor_kind` (`user` | `system`), `actor_id` (null for `system`), `payload` JSONB, `occurred_at`, `event_id` (unique, consumer idempotency). RLS, GIN on `payload`, index `(tenant_id, occurred_at DESC)`. Kept forever (no purge; Phase 8 GDPR anonymises).
- `AuditEventPort` + `AuditEvent` record in `shared:common`; adapters in `shared:web`. Registry **and gateway** (same `registry` schema): Jdbc adapter, same transaction as the mutation — gateway needs no Kafka producer (decided 2026-10-29). Topology, ingestion: Kafka adapter → `cartogra.platform.audit.recorded`, sent after commit off the request thread; registry consumes idempotently on `event_id`.
- Payload = changed fields `{changes: {field: {from, to}}}` for updates, key fields for create/delete. Per-entity field allowlist — tokens, kubeconfigs, password hashes never enter.
- Emit on **state changes only**:
  - registry: service create/update/delete, discovery merge, team CRUD + membership, ownership, tier.
  - topology: declared edge CRUD, observed edge first seen, drift detected/resolved. NOT per span / `last_seen` bumps.
  - ingestion: SCM connection + cluster CRUD, sync completed/failed.
  - gateway: user invited / role changed / disabled / enabled, OIDC config. NOT logins, refreshes, guest mints.
- `GET /api/v1/registry/audit-events`: tenant-scoped, paginated (`PageResult`), filters `entityType`, `entityId`, `actorKind`, `from`/`to`. Readable by **every role incl. GUEST** (not admin-only).
- `timeline.tsx` drops `MOCK_TIMELINE`.
- Tests: Jdbc adapter IT; consumer idempotency IT (same `event_id` twice → one row); per-service IT asserting one representative mutation emits; payload allowlist unit test (secret field never present).

## 3. Dashboard on real data (#126)

- Only `ActivityList` still reads `MOCK_TIMELINE` (health summary + teams already real); wire it to `GET /v1/audit-events?size=5`. Top risks from 2.5 if not yet wired.
- `dashboard.tsx` has no `MOCK_*`; Vitest loading / error / empty.

## 4. Guest mode + signup off (#128, signup part of #129)

ADR-0019 → Accepted + amended (no ADR-0030 for this).

- `POST /api/auth/guest` mints a JWT: `sub` = random UUID (not `guest-` prefix — downstream parses `X-User-Id` as UUID), `tid` = demo tenant (config `app.guest.tenant-id`), `roles=["GUEST"]`, 60 min, httpOnly cookie, no refresh token. Disabled when `app.guest.tenant-id` is unset.
- **Gateway deny (primary guard)**: GUEST + method not GET/HEAD/OPTIONS → 403 envelope. Allowlist: `/api/auth/guest`, `/api/auth/logout`. Fail-closed for future endpoints.
- **Rate limits**: `/api/auth/guest` per IP (≈5/min); guest API calls bucketed per guest `sub`, not the shared demo-tenant bucket.
- **Backend sweep (secondary, fixes VIEWER today)**: topology's 6 write endpoints get MEMBER/ADMIN guards (0 today); registry `TeamService` drops `TEAM_OWNER` for ADMIN-or-member per ADR-0025; every write in registry/topology/ingestion checked.
- **Frontend**: `/demo` route mints and lands on dashboard; login page "Try the demo"; on 401 in a guest session re-mint silently and retry; persistent "Read-only demo" banner; write controls visible but disabled with tooltip; Settings/admin hidden from nav.
- **Signup off**: `app.signup.enabled=false`: `POST /api/auth/register` → 403 `SIGNUP_DISABLED`; `OAuthService.resolveNewTenantUser` refuses new tenants (existing users + invites unaffected); frontend hides signup. IT covers both guards.
- Tests: IT enumerates every mapped write route (from handler mappings, not a hand list) with a guest token → 403; guest reads 200; IT for re-mint and per-sub rate limit. E2E `frontend/e2e/guest-demo.spec.ts`: `/demo` → dashboard activity → blast radius → disabled buttons → direct API write 403.

## 5. Helm charts (#148, 7.1 pulled into Phase 4)

- Per-service charts (gateway, registry, topology, ingestion, frontend, collector) + umbrella; Postgres, Kafka, Valkey as single-replica StatefulSets (local-path PVs).
- infra.md probes, resources (requests + limits), pod/container securityContext, `/tmp` emptyDir, labels applied everywhere.
- HA (replicas, PDB, HPA) via values; `values-demo.yaml` sets replicas 1, PDB/HPA off — ADR-0030.
- Secrets mounted as volumes from Sealed Secrets.
- **Cleanup** (charts replace them): port probes/resources/securityContext from `infra/k8s/base` into the charts, then delete `infra/k8s/base`, `infra/k8s/overlays`, `infra/k8s/infra/otel-collector`, `infra/k8s/infra/namespace.yml`, and `infra/k8s/cluster/` (personal kubeadm lab, not deploy code). Keep `infra/k8s/infra/helm/*` (monitoring values) for 7.6. Repoint `docs/runbooks/quickstart.md` §53 to the charts.
- Multi-arch images (`linux/amd64,linux/arm64`) via `docker buildx` in CI, Acme fake-service image included.
- `values-demo.yaml` sized for 8GB per ADR-0030: JVMs 512Mi limit, `-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k`; Kafka `-Xmx384m`; Postgres `shared_buffers=128MB`.
- `values-demo.yaml` sets gateway `GUEST_TENANT_ID` = the Acme tenant's fixed UUID (step 6 bootstrap) and `SIGNUP_ENABLED=false` (step 4 defaults: guest off, signup on).
- `values-demo.yaml` sets topology `TOPOLOGY_DRIFT_MISSING_AFTER=PT1H` (default `P7D` would hide the seeded `loyalty-service → fx-service` missing drift for a week after install). Only safe with continuous in-cluster Acme traffic (step 6) — bursty traffic + short window makes missing drift flap and floods audit/Timeline (seen in dev 2026-11-03 with `PT2M`).

Charts: `infra/helm/cartogra` (umbrella + subcharts, `charts/common` library). Demo collector config lives in the collector chart (`files/otel-collector.yml`) and compose mounts it. Acme tenant fixed UUID `a0000000-0000-4000-8000-00000000ac3e` (values-demo; step 6 bootstrap must create it). CI: `helm.yml` (lint + render prod/demo), `images.yml` (amd64+arm64, no push until step 6).

Done when: `helm lint` + `helm template` clean; umbrella installs on local k3d.

## 6. Deploy pipeline + in-cluster Acme (#129, code)

- CI: main pushes SHA-tagged images to GHCR; `workflow_dispatch` job runs `helm upgrade` with a kubeconfig secret.
- Acme live in-cluster: fake services as pods in namespace `acme-fintech`; the gateway tenant bootstrap creates the Acme tenant (fixed UUID); a Helm post-install bootstrap Job registers the in-cluster cluster with a read-only ServiceAccount token, connects the GitHub mirror, runs `acme load`.
- Nightly `pg_dump` CronJob to object storage.
- Rewrite `docs/runbooks/incident-response.md` for the demo host and add the in-cluster ServiceAccount case to `docs/runbooks/kubernetes-cluster-config.md`.
- Rewrite `docs/runbooks/deployment.md`: VM → k3s → sealed-secrets key restore → helm install → bootstrap Job → verify.

Decisions (2026-11-03):

- Acme tenant: generic gateway startup bootstrap `app.bootstrap.tenant` (`BOOTSTRAP_*` env, off by default; first tenant + verified admin when signup is off). No demo code in the product: only `values-demo.yaml` sets Acme's ID, name, business plan. No SQL from the Job, no new endpoint.
- Cluster registration: chart creates a read-only ServiceAccount (get/list/watch services, endpoints, namespaces) + token Secret; the Job registers `https://kubernetes.default.svc` with that token and CA via the normal API (`MANUAL` source, no kubeconfig). No `IN_CLUSTER` source.
- Acme: separate chart `infra/helm/acme` (namespace `acme-fintech`, same as the seed and discovery `externalId`s), services from `files/services.json` generated by `pnpm acme manifests --helm` (a seed test checks sync); post-install/upgrade hook Job registers cluster + SCM, runs `acme load`.
- Team permissions: frontend aligned with backend — any writer (MEMBER, TEAM_OWNER) creates teams; writers who are team members manage them (ADR-0025). `GET /api/auth/admin/users` opened to MEMBER for the add-member picker.
- Backups: `pg_dump` CronJob to Hetzner Object Storage (S3 API; endpoint/bucket values), keys in SealedSecret, 7-day lifecycle.

Status (2026-11-03, k3d): both charts install; bootstrap Job registers the cluster via the reader token, discovery finds 21 services, `acme load` gives 5 teams / 18 owned / 43 declared / 2 cycles; 42 observed edges from live spans; guest read 200, write 403; second upgrade re-runs the Job with no duplicates; backup dump OK (upload untested without real S3). GHCR push + `deploy.yml` untested until merged to main. 17h soak (2026-11-04): 0 Acme pod restarts, one drift record total (the seed's intentional never-called `loyalty-service -> fx-service`), no repeats, so no flapping. `AdminUsersAccessIT` covers the MEMBER/TEAM_OWNER opening of `GET /api/auth/admin/users`. Kafka startup race fixed: clients use a ClusterIP `kafka` Service (name resolves before the broker is Ready), headless `kafka-headless` governs the StatefulSet; JVM services started with Kafka down 3 min had 0 restarts and all consumer groups rejoined.

Abuse alerting (decided 2026-11-04, in-cluster option): `charts/alerting` (off by default, on in `values-demo.yaml`) runs Prometheus + Alertmanager in one pod. Collector exposes metrics on 8889, Prometheus keeps only `cartogra_*`, Alertmanager emails through Resend SMTP on port 587 with the existing `RESEND_API_KEY`. k3d: 75 rate-limited mints fired `GuestMintsRateLimited`, Alertmanager reached Resend, which rejected the placeholder key (535); pod used 40Mi with 15 series. Real delivery is checked in step 7.

Guest second key (2026-11-04): guest mode needs `tenants.public_sandbox` as well as `GUEST_TENANT_ID` (ADR-0019 §10). Set only when bootstrap creates the tenant, so an existing tenant cannot be flagged by config. `/api/auth/features` reads it per request with no cache: an indexed lookup behind the auth rate limit.

Done when: chart + bootstrap Job run on local k3d and Acme is discovered; Acme fake services generate traffic continuously (no gaps > `missing-after`), so drift does not flap.

## 7. Ops — public host (#129)

**7a. NetworkPolicy (code, before the first `helm install`).** Layer 1 of #151, pulled forward: backend services trust `X-Tenant-Id` / `X-User-*` headers, so on a shared cluster any pod could call them directly and forge tenant or admin. The signed gateway token (layer 2) stays in #151 / Phase 7.

- `common.app` (`infra/helm/cartogra/charts/common/templates/_app.tpl`): optional `NetworkPolicy` per app, rendered when `networkPolicy.allowFrom` is set. Ingress only on the app port, only from pods with `app: <name>` in the list.
- Allow lists: `registry` ← gateway, ingestion, topology (both call it via `REGISTRY_URI`); `topology` ← gateway; `ingestion` ← gateway. Gateway and frontend stay open (Ingress controller reaches them).
- Kubelet probes are not subject to NetworkPolicy; OTel export is outbound — no rules needed.
- k3s enforces NetworkPolicy out of the box (kube-router); note in `deployment.md` that a CNI without enforcement silently ignores it.
- Verify on the host: `kubectl run curl --rm -it --image=curlimages/curl -- curl -m 3 http://ingestion:8085/actuator/health` from a non-gateway pod times out; the app (login, `/demo`, discovery, Acme bootstrap) still works end to end.
- Out of scope: Postgres/Valkey/Kafka policies (password-protected; revisit in #151).
- **Done (code):** template + allow lists, chart README, `deployment.md` (k3s note + verify command), `local-test.sh` step "Check NetworkPolicy". k3d run green; removing the ingestion policy makes the probe pod reach ingestion (check is not vacuous). Host verify left for 7b.

**7b. Host setup.** Human steps, following the rewritten `deployment.md` (doubles as its test):

- Hetzner **CAX21 (ARM, 4 vCPU, 8GB)**, resize to CAX31 only on measured pressure; single-node k3s, Traefik (bundled) + cert-manager Let's Encrypt on `cartogra.dev`.
- Sealed Secrets controller, restored key on a rebuild; encrypted `SealedSecret`s committed.
- GitHub mirror: `pnpm acme repos --provider github` pushes the 21 repos to a public org; demo syncs with a read-only public-repos token.
- `infra/k8s/cluster-setup.yaml` (namespaces, CI `deployer` ServiceAccount; admin kubeconfig never in CI), `KUBECONFIG` CI secret, `ALERT_EMAIL` variable; first `helm install`; send the test alert (`deployment.md` section 8).
- External uptime check on `/api/auth/features` through ingress (actuator is not routed).

## 8. Gate (#130)

Roadmap Phase 4 gate + additions below; open PR from `docs/phase-4-plan`.

## E2E

- New `guest-demo.spec.ts` (step 4).
- `demo-flow.spec.ts` asserts loader writes appear on Timeline (step 2).

## Gate additions (on top of roadmap gate)

- `/demo` gets a stranger in with no credentials; no signup path on the public host.
- Audit events from all four services; readable by GUEST.
- Runbook rebuilds the k3s host from scratch, including the bootstrap Job.
