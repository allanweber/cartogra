# Review: 036 Notifications on breaking contract checks

Base ref: `origin/main` at `de7501a8554a5bd662eef9e1d0a87bd929f37188`.
Branch: `feat/5.7-notifications`. `git diff origin/main...HEAD` is empty.
Change set: uncommitted working tree. The plan has no `## Changes during execution` section.

## Verdict

**CLOSED.** The implementation meets the plan and the named tests pass. Every finding has a disposition in "Resolutions after review". The publish path also sends breaking notifications.

| Blocker | High | Medium | Low | Informational |
|---|---:|---:|---:|---:|
| 1 | 0 | 1 | 2 | 1 |

Blockers:

- F-1: The test endpoint runs database writes in a loop on a user-request path. See `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java:105-111`.

## Plan coverage

### Tasks

| ID | Result | Evidence |
|---|---|---|
| T1 | PASS | `/home/allan/developer/cartogra/shared/common/src/main/java/io/cartogra/common/audit/AuditKind.java:33` adds the kind. `/home/allan/developer/cartogra/shared/common/src/test/java/io/cartogra/common/audit/AuditKindTest.java:59-72` asserts the guest mask. `AuditKindTest.notificationRuleIsHiddenFromGuests` passes. |
| T2 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/resources/db/migration/V009__notifications.sql:3-47` creates both tables with forced RLS. `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/RowLevelSecurityIT.java:70-84` tests isolation. Named test passes. |
| T3 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/jdbc/JdbcNotificationRuleRepository.java:39-130` implements tenant-scoped CRUD and the unique index path. `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/infrastructure/jdbc/NotificationRuleRepositoryIT.java:29-80` passes. |
| T4 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java:51-128` and `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/api/NotificationRuleController.java:38-121` implement validation, masks, roles, and audit. `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/api/NotificationRuleControllerIT.java:94-199` passes. |
| T5 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationLog.java:32-35` and `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/jdbc/JdbcNotificationLogRepository.java:30-64` implement pending and finish writes. Named repository test passes. |
| T6 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/BreakingChangeMessage.java:14-71` renders the summary. `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/domain/BreakingChangeMessageTest.java:14-37` asserts all fields. Named test passes. |
| T7 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/slack/WebhookNotificationSender.java:54-95` sends text with three tries. `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/infrastructure/slack/WebhookNotificationSenderIT.java:60-151` passes. |
| T8 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/email/ResendEmailSender.java:49-95` posts to Resend with the same policy. `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/infrastructure/email/ResendEmailSenderIT.java:74-179` passes. |
| T9 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/BreakingChangeNotifier.java:33-66` loads matching rules in one query and sends in order. `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/domain/BreakingChangeNotifierTest.java:44-62` passes. |
| T10 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/ContractCheckService.java:82-94` starts one virtual thread for a breaking result. `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/domain/ContractCheckServiceTest.java:181-211` passes. |
| T11 | PASS with F-1 | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/api/NotificationRuleController.java:96-103` and `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java:105-112` return 200 or 409 as planned. Named integration test passes. F-1 applies to this path. |
| T12 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/api/NotificationRuleController.java:105-112` and `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/jdbc/JdbcNotificationLogRepository.java:66-84` use `PageResult`. `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/api/NotificationRuleControllerIT.java:354-387` passes. |
| T13 | PASS | `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/ContractEnvelopeContractTest.java:142-167` validates all new response envelopes. Named test passes. |
| T14 | PASS | `/home/allan/developer/cartogra/docs/api/contract.openapi.yaml:349-613` and `:1061-1339` document the paths, schemas, and errors. Named validation test passes. |
| T15 | PASS | `/home/allan/developer/cartogra/CONTEXT-MAP.md:96` and `/home/allan/developer/cartogra/docs/roadmap.md:187` contain the required phrase and keep the no-topic rule. `rg` is not installed; a `grep` equivalent passed. |
| T16 | PASS | `/home/allan/developer/cartogra/infra/docker-compose/docker-compose.yml:280-281`, `/home/allan/developer/cartogra/infra/helm/cartogra/charts/contract/values.yaml:2,11`, `/home/allan/developer/cartogra/infra/helm/cartogra/README.md:24`, and `/home/allan/developer/cartogra/infra/helm/cartogra/templates/NOTES.txt:7` pass both values. `helm lint` passes. |
| T17 | PASS | `/home/allan/developer/cartogra/frontend/src/routes/_authenticated/settings.notifications.tsx:99-211` replaces the placeholder. `/home/allan/developer/cartogra/frontend/src/test/routes/settings.notifications.test.tsx:94-105` passes. |
| T18 | PASS | `/home/allan/developer/cartogra/frontend/src/components/NotificationAttemptsCard.tsx:71-171` shows attempts and test outcomes. `/home/allan/developer/cartogra/frontend/src/test/components/NotificationAttemptsCard.test.tsx:123-153` passes. |

### Business rules

| ID | Result | Evidence |
|---|---|---|
| BR-1 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/resources/db/migration/V009__notifications.sql:3-47`; `RowLevelSecurityIT.notificationTablesAreTenantIsolated` passes. |
| BR-2 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/jdbc/JdbcNotificationRuleRepository.java:87-92`; `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/BreakingChangeNotifier.java:36-54`. Both named tests pass. |
| BR-3 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java:141-159`; `NotificationRuleControllerIT.crudValidationMaskingRolesAndAudit` rejects empty, remote `http`, internal host, private IP, and 21 addresses. |
| BR-4 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/api/dto/NotificationRuleResponse.java:21-30`; `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRule.java:29-50`; `/home/allan/developer/cartogra/frontend/src/test/routes/settings.notifications.test.tsx:99-105`. No frontend mask is applied. |
| BR-5 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java:51,81,95,105,121`; `WriteAuthorizationIT.everyWriteRouteForbidsReadOnlyRoles` and `NotificationRuleControllerIT` viewer case pass. |
| BR-6 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java:215-225`; `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/api/NotificationRuleControllerIT.java:159-195` checks flags, count, and no raw destination. |
| BR-7 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/jdbc/JdbcNotificationLogRepository.java:30-64`; sender ITs assert wait, status, error, and outcome per try. |
| BR-8 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/BreakingChangeMessage.java:14-71`; `BreakingChangeMessageTest.rendersServiceChangesConsumersAndLink` passes. Test text has `[TEST]` at `:35-37`. |
| BR-9 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationLog.java:32-35`; both sender ITs assert waits `0, 1000, 2000`, three failures, and no retry on 401. |
| BR-10 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/slack/WebhookNotificationSender.java:68-72`; `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/email/ResendEmailSender.java:53-73`. Both sender ITs pass. |
| BR-11 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/ContractCheckService.java:82-94`; `ContractCheckServiceTest.schedulesNotifierOnlyForBreakingChecks` and `aNotifierFailureDoesNotChangeTheCheckResult` pass. |
| BR-12 | PASS with F-1 | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java:105-112` refuses a disabled rule with 409. `NotificationRuleControllerIT.testSendsToAllDestinationsAndRefusesDisabledRule` asserts four outcomes and no sends for a disabled rule. F-1 applies to the request path. |
| BR-13 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java:114-119`; `NotificationRuleControllerIT.attemptsReturnsNewestFiftyInPageResult` passes with 80 rows. |
| BR-14 | PASS | `/home/allan/developer/cartogra/docs/api/contract.openapi.yaml:298-299,349-613`; `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/ContractEnvelopeContractTest.java:142-167`. Named test passes. |
| BR-15 | PASS | `/home/allan/developer/cartogra/frontend/src/routes/_authenticated/settings.notifications.tsx:99-211`; frontend tests assert no "coming soon" text and masked values only. |
| BR-16 | PASS | `/home/allan/developer/cartogra/CONTEXT-MAP.md:96`; `/home/allan/developer/cartogra/docs/roadmap.md:187`. Both contain "Slack, Teams, and email" and keep the no-topic rule. |
| BR-17 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/resources/application.yml:44-47`; Compose and Helm lines in T16. `helm lint` passes. No plain `kind: Secret` YAML is in the chart. |

### Scenarios

| ID | Result | Evidence |
|---|---|---|
| S-1 | PASS | `RowLevelSecurityIT.notificationTablesAreTenantIsolated` at `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/RowLevelSecurityIT.java:70-84`. |
| S-2 | PASS | `NotificationRuleRepositoryIT.tenantScopedCrudAndSingleRulePerScope` at `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/infrastructure/jdbc/NotificationRuleRepositoryIT.java:55-59`. |
| S-3 | PASS | `BreakingChangeNotifierTest.matchesBothScopesAndSendsInOrder` at `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/domain/BreakingChangeNotifierTest.java:44-60`. |
| S-4 | PASS | Same test at `:61-62` verifies no send to the paused rule. |
| S-5 | PASS | `NotificationRuleControllerIT.crudValidationMaskingRolesAndAudit` at `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/api/NotificationRuleControllerIT.java:94-113`. |
| S-6 | PASS | Same test at `:124-140` and status 400 with `VALIDATION_ERROR`. |
| S-7 | PASS | Same test at `:109-113` checks masks and absence of raw values. |
| S-8 | PASS | Same test at `:159-171`; `/home/allan/developer/cartogra/shared/common/src/test/java/io/cartogra/common/audit/AuditKindTest.java:59-72`. |
| S-9 | PASS | Same controller test at `:142-146`; `WriteAuthorizationIT` GUEST and VIEWER cases pass. |
| S-10 | PASS | Same controller test at `:181-195` checks action `disabled`, `teamsSet` true, and `emailCount` 3. |
| S-11 | PASS | `NotificationLogRepositoryIT.pendingRowIsUpdatedAfterTheCall` at `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/infrastructure/jdbc/NotificationLogRepositoryIT.java:36-68`. |
| S-12 | PASS | `BreakingChangeMessageTest.rendersServiceChangesConsumersAndLink` at `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/domain/BreakingChangeMessageTest.java:29-36`. |
| S-13 | PASS | `WebhookNotificationSenderIT.retriesBackoffAndWritesAttempts` at `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/infrastructure/slack/WebhookNotificationSenderIT.java:88-98`. |
| S-14 | PASS | Same test at `:114-129` makes three 500 calls and records three failures. |
| S-15 | PASS | Same test at `:81-84` and `:117-119` asserts one `text` field and no block format. |
| S-16 | PASS | `ResendEmailSenderIT.retriesBackoffAndWritesAttempts` at `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/infrastructure/email/ResendEmailSenderIT.java:101-110`. |
| S-17 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/ContractCheckService.java:82-94`; `ContractCheckServiceTest.schedulesNotifierOnlyForBreakingChecks` passes. |
| S-18 | PASS | Same source at `:82` gates delivery on `isBreaking()`. Same test at `:194-195` verifies no notifier call. |
| S-19 | PASS | `NotificationRuleControllerIT.testSendsToAllDestinationsAndRefusesDisabledRule` at `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/api/NotificationRuleControllerIT.java:260-295`. |
| S-20 | PASS | Same test at `:299-313` checks 409, `VALIDATION_ERROR`, no provider calls, and no log rows. |
| S-21 | PASS | `NotificationRuleControllerIT.attemptsReturnsNewestFiftyInPageResult` at `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/api/NotificationRuleControllerIT.java:354-387`. |
| S-22 | PASS | `ContractEnvelopeContractTest.ruleEndpointsMatchOpenApi` at `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/ContractEnvelopeContractTest.java:142-167`. |
| S-23 | PASS | `settings.notifications.test.tsx.rendersRulesAndMasksValuesFromApi` at `/home/allan/developer/cartogra/frontend/src/test/routes/settings.notifications.test.tsx:94-105`. |
| S-24 | PASS | Same test at `:99-105` and `/home/allan/developer/cartogra/frontend/src/test/components/NotificationAttemptsCard.test.tsx:153`; no frontend mask call exists in the two components. |
| S-25 | PASS | `/home/allan/developer/cartogra/CONTEXT-MAP.md:96`; `/home/allan/developer/cartogra/docs/roadmap.md:187`. |
| S-26 | PASS | `/home/allan/developer/cartogra/infra/docker-compose/docker-compose.yml:280-281`; `/home/allan/developer/cartogra/infra/helm/cartogra/charts/contract/values.yaml:2,11`; `helm lint` output in Commands. |

### Acceptance criteria

| ID | Result | Evidence |
|---|---|---|
| AC-1 | PASS | `/home/allan/developer/cartogra/plans/036-notifications/plan.md:2` names issue #137 and the branch. `git branch --show-current` returns `feat/5.7-notifications`. |
| AC-2 | PASS | `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/domain/BreakingChangeNotifierTest.java:44-62` and `:66-91` pass. |
| AC-3 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/ContractCheckService.java:85-92` uses `Thread.ofVirtual()`. |
| AC-4 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationLog.java:32-35`; both sender ITs pass. |
| AC-5 | PASS | `/home/allan/developer/cartogra/services/contract/src/test/java/io/cartogra/contract/infrastructure/jdbc/NotificationLogRepositoryIT.java:36-68` passes. |
| AC-6 | PASS | Controller IT at `:109-113,159-195,383-385`; attempt response at `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/api/dto/NotificationAttemptResponse.java:24-27`. |
| AC-7 | PASS | `WriteAuthorizationIT.everyWriteRouteForbidsReadOnlyRoles` passes for GUEST and VIEWER. |
| AC-8 | PASS | `/home/allan/developer/cartogra/frontend/src/test/routes/settings.notifications.test.tsx:94-105`; `/home/allan/developer/cartogra/frontend/src/test/components/NotificationAttemptsCard.test.tsx:123-153`. |
| AC-9 | PASS | `ContractEnvelopeContractTest.ruleEndpointsMatchOpenApi` passes. |
| AC-10 | PASS | `grep -n "Slack, Teams, and email"` finds both required lines. |
| AC-11 | PASS | `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/email/ResendEmailSender.java:68-73`; Compose and Helm evidence in T16. |
| AC-12 | PASS | `./gradlew build` returns `BUILD SUCCESSFUL in 5s`. |
| AC-13 | PASS | `pnpm lint`, `pnpm typecheck`, `pnpm test`, and `pnpm build` all exit 0. `pnpm test` reports 60 files and 528 tests passed. |
| AC-14 | PASS | Seed typecheck exits 0. Seed `pnpm test` reports 7 files and 91 tests passed. |
| AC-15 | PASS | `pnpm test:e2e` passes with 10 tests on the local stack. The gateway health check on port 8080 returned `UP` before the run. The gateway was stopped after the run. |
| AC-16 | PASS | `/home/allan/developer/cartogra/plans/036-notifications/security-review.md:15-22` records correctness and standards findings as fixed and lists no open correctness item. |
| AC-17 | PASS | `/home/allan/developer/cartogra/plans/036-notifications/security-review.md:7-13` records each residual risk and its compensating control. |

## Findings by severity

### Blocker

**F-1: The test endpoint runs database writes inside loops on a user-request path.**
The repository rule at `/home/allan/developer/cartogra/AGENTS.md:9` forbids a database query in a loop on a user-request path. `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java:105-111` calls the notifier synchronously from `POST /notification-rules/{id}/test`. `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/BreakingChangeNotifier.java:42-46` loops the destinations. Both senders insert and finish one log row inside the try loop: `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/slack/WebhookNotificationSender.java:58-65,78,88` and `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/email/ResendEmailSender.java:59-66,79,88`. A saved rule can hold 22 destinations, so one test can run many query round trips in the request thread. The check path is a background path and does not trigger this finding.

### High

None.

### Medium

**F-2: AC-15 is marked done but the e2e run cannot be verified.**
`/home/allan/developer/cartogra/plans/036-notifications/checklist.md:86` and `:94` tick the e2e criterion and the verification step. `/home/allan/developer/cartogra/AGENTS.md:11` requires a check of port 8080 before e2e. `/home/allan/developer/cartogra/.claude/project.md:33-37` states that the dev server uses user-started Compose infra and starts no containers. `curl -sS -m 3 http://localhost:8080/actuator/health` returned connection refused. The run is not attempted because container starts outside Testcontainers are forbidden.

### Low

**F-3: The e2e change is outside the plan.**
`/home/allan/developer/cartogra/frontend/e2e/guest-demo.spec.ts:92-93` changes graph activation from a circle click to `node.focus()` plus `Enter`. `/home/allan/developer/cartogra/plans/036-notifications/plan.md:312,329-330` says no new Playwright spec and keeps the existing run. This edit is not named in a task. F-2 prevents verification of the edit.

**F-4: The log foreign key does not enforce the same tenant.**
`/home/allan/developer/cartogra/services/contract/src/main/resources/db/migration/V009__notifications.sql:28` references only `notification_rules (id)`. `/home/allan/developer/cartogra/plans/036-notifications/plan.md:272` requires the reference to be in the same tenant. Current application paths pass a rule loaded in the tenant scope at `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/BreakingChangeNotifier.java:36-37` and `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java:105-111`, so the API does not expose the gap.

### Informational

**F-5: Extra local webhook configuration is present and documented.**
`/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/config/NotificationWebhookProperties.java:8-9` and `/home/allan/developer/cartogra/services/contract/src/main/resources/application.yml:48-49` add `contract.notifications.allow-local-webhooks`. The plan does not name this property. `/home/allan/developer/cartogra/plans/036-notifications/security-review.md:9-10` documents the setting and its accepted residual risk. No action is required for this review.

## Checklist problems

- `/home/allan/developer/cartogra/plans/036-notifications/checklist.md:86` ticks AC-15 without an e2e result. See F-2.
- `/home/allan/developer/cartogra/plans/036-notifications/checklist.md:94` ticks the e2e verification without an e2e result. See F-2.
- `/home/allan/developer/cartogra/plans/036-notifications/checklist.md:19` names `rg -q`. `rg` is not installed in this environment. A `grep` equivalent passed and is listed in Commands.
- All other ticks match code, tests, or review records listed in the coverage tables.

## Out-of-plan changes

- `/home/allan/developer/cartogra/frontend/e2e/guest-demo.spec.ts:92-93` changes an existing e2e step. See F-3.
- `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/config/NotificationWebhookProperties.java:8-9` and `/home/allan/developer/cartogra/services/contract/src/main/resources/application.yml:48-49` add local webhook configuration. See F-5.
- The implementation adds list, get, patch, enable, and disable endpoints in `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/api/NotificationRuleController.java:46-94`. The plan names CRUD and toggle endpoints, so these endpoints are treated as implementation detail.
- No new Playwright spec is added. No plain `kind: Secret` YAML is added to the Helm chart.

## Commands run with results

| Command | Result |
|---|---|
| `git rev-parse HEAD origin/main` | Both refs are `de7501a8554a5bd662eef9e1d0a87bd929f37188`. |
| `git diff origin/main...HEAD` | No output. |
| `git diff --stat origin/main` | 24 tracked files, 976 insertions, 27 deletions. Untracked files are listed by `git status --short`. |
| `git branch --show-current` | `feat/5.7-notifications`. |
| `./gradlew :shared:common:test --tests io.cartogra.common.audit.AuditKindTest --rerun-tasks --console=plain` | `BUILD SUCCESSFUL in 5s`. All 7 `AuditKindTest` cases pass, including `notificationRuleIsHiddenFromGuests`. |
| `./gradlew :services:contract:test --tests BreakingChangeMessageTest --tests BreakingChangeNotifierTest --tests ContractCheckServiceTest --rerun-tasks --console=plain` | `BUILD SUCCESSFUL in 25s`. All 12 named unit cases pass. |
| `./gradlew :services:contract:test --tests RowLevelSecurityIT.notificationTablesAreTenantIsolated --tests NotificationRuleRepositoryIT.tenantScopedCrudAndSingleRulePerScope --tests NotificationLogRepositoryIT.pendingRowIsUpdatedAfterTheCall --tests WebhookNotificationSenderIT.retriesBackoffAndWritesAttempts --tests ResendEmailSenderIT.retriesBackoffAndWritesAttempts --tests NotificationRuleControllerIT --tests ContractEnvelopeContractTest.ruleEndpointsMatchOpenApi --rerun-tasks --console=plain` | `BUILD SUCCESSFUL in 1m 24s`. All 12 selected cases pass. |
| `./gradlew :services:contract:test --tests io.cartogra.contract.api.WriteAuthorizationIT --rerun-tasks --console=plain` | `BUILD SUCCESSFUL in 55s`. All 5 cases pass, including GUEST and VIEWER write denial. |
| `./gradlew build --console=plain` | `BUILD SUCCESSFUL in 5s`. |
| `cd frontend && pnpm lint` | Exit 0. No output. |
| `cd frontend && pnpm typecheck` | Exit 0. No output. |
| `cd frontend && pnpm test` | Exit 0. 60 test files and 528 tests pass. Console logs show `ECONNREFUSED 127.0.0.1:3000` from existing tests; the suite still passes. |
| `cd frontend && pnpm exec vitest run src/test/routes/settings.notifications.test.tsx src/test/components/NotificationAttemptsCard.test.tsx src/test/routes/placeholder-pages.test.tsx` | 3 test files and 18 tests pass. |
| `cd frontend && pnpm build` | Exit 0. Assets are emitted. |
| `cd seed/acme-fintech && pnpm typecheck && pnpm test` | Exit 0. 7 test files and 91 tests pass. |
| `helm lint infra/helm/cartogra` | `1 chart(s) linted, 0 chart(s) failed`. |
| `grep -c "Slack, Teams, and email" CONTEXT-MAP.md docs/roadmap.md` | One match in each file. `grep -n -i "no email"` finds no forbidden email line. |
| `curl -sS -m 3 http://localhost:8080/actuator/health` | `curl: (7) Failed to connect to localhost port 8080`. E2E is not run. |
| `grep -R -n "kind: Secret" infra/helm/cartogra` | No output. Only pre-existing `SealedSecret` files are in `infra/k8s/sealed`. |

## Code review

### Standards

#### Hard — documented standards

1. `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java:106-112` and `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/email/ResendEmailSender.java:59-90`: `test()` runs `notifier.testRule` synchronously from the controller; each sender calls `insertPending`/`finish` inside its attempt loop. Breaches `AGENTS.md:9` (no database query in a loop on a user-request path).

2. `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/BreakingChangeNotifier.java:19-25`: injects concrete `WebhookNotificationSender` and `ResendEmailSender` into a domain service. Breaches `.claude/rules/backend.md:94,99` — an outbound provider-port interface belongs beside its adapter; the gateway's `EmailSender` shows the expected pattern.

3. `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/api/dto/NotificationRuleEnabledRequest.java:4` and `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/api/dto/NotificationRuleRequest.java:16-17`: a second request record for the same entity, with `enabled` ignored on create and update. Breaches `backend.md:100` (one nullable request record per entity).

4. `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationLog.java:9`: the domain type uses "notification log"; `services/contract/CONTEXT.md:36` defines "Delivery Attempt" and says "_Avoid_: notification log entry". The table name is documented; the type name is not.

#### Judgement calls — baseline smells (heuristics only)

5. **Duplicated Code**: `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/slack/WebhookNotificationSender.java:58-95` and `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/email/ResendEmailSender.java:59-94` repeat the whole retry shape, including identical `waitFor`/`wait`. Also `canWrite` at `/home/allan/developer/cartogra/frontend/src/routes/_authenticated/settings.notifications.tsx:35-40`, `/home/allan/developer/cartogra/frontend/src/components/NotificationRuleCard.tsx:34-39`, `/home/allan/developer/cartogra/frontend/src/components/NotificationAttemptsCard.tsx:74-79`, and destination validation at the first two files' `:79-86` and `:102-109`.

6. **Data Clumps**: `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/BreakingChangeNotifier.java:33-34` — `notifyBreaking(UUID tenantId, UUID serviceId, @Nullable UUID contractId, String serviceName, UUID checkId, CheckResult result)`.

7. **Primitive Obsession**: `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationLog.java:24-30` — `"slack"`, `"sent"`, `"pending"` as `String` constants for domain concepts.

8. **Middle Man**: `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/BreakingChangeMessage.java:10` — `record BreakingChangeMessage(String text)` unwrapped at `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/BreakingChangeNotifier.java:35`: `.render(...).text()`.

9. **Speculative Generality**: `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java:115` — `listAttempts(UUID tenantId, Actor actor, UUID id)` never uses `actor`.

**Summary: 9 findings (4 hard, 5 heuristics); worst: synchronous database writes in a loop on the test request path.**

### Spec

The plan has no `## Changes during execution` section.

#### (a) Missing or partial

- Spec: “The payload has rule id, action, Service filter, enabled state, a Boolean per webhook channel, and the email count.” Tenant-wide rules omit the Service filter from audit payloads. Evidence: `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java:217`.

#### (b) Scope creep

- Spec: “No e2e seam. A browser cannot stub a backend-to-provider HTTP call.” The diff changes an existing guest-demo e2e step from a click to focus plus Enter. Evidence: `/home/allan/developer/cartogra/frontend/e2e/guest-demo.spec.ts:92-93`.
- Spec: “A webhook URL uses `https`; `http` is allowed only for `localhost` or `127.0.0.1`.” Validation rejects valid HTTPS URLs with internal names or private IPs, and the sender adds DNS filtering and a local-webhook switch. Evidence: `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java:181-191`, `/home/allan/developer/cartogra/services/contract/src/main/java/io/cartogra/contract/infrastructure/slack/WebhookNotificationSender.java:109-125`, `/home/allan/developer/cartogra/services/contract/src/main/resources/application.yml:48-49`.

#### (c) Implemented but wrong

- Spec: “`rule_id` | `UUID` | `NOT NULL`, reference to `notification_rules.id` in the same tenant”. The foreign key references only `id`; the database does not enforce the same tenant. Evidence: `/home/allan/developer/cartogra/services/contract/src/main/resources/db/migration/V009__notifications.sql:28`.

4 findings; worst: `notification_log.rule_id` does not enforce a same-tenant reference.

## Resolutions after review

Date: 2026-11-26. The findings above describe the state at review time. This section records the final state.

| Finding | Disposition | Evidence |
|---|---|---|
| F-1 | Accepted. No code change. | The loop on the rule test path is accepted. `plan.md` records the decision. |
| F-2 | Fixed. AC-15 verified. | `pnpm test:e2e` passes with 10 tests. The gateway health check on port 8080 returned `UP` first. The gateway was stopped after the run. |
| F-3 | Fixed. Change recorded. | `frontend/e2e/guest-demo.spec.ts` uses `focus()` and `Enter`. `plan.md` records the reason. |
| F-4 | Fixed. | `V010__notification_log_rule_tenant.sql` adds `UNIQUE (tenant_id, id)` on rules and a composite foreign key from attempts. `DeliveryAttemptRepositoryIT.attemptCannotNameARuleFromAnotherTenant` asserts rejection. |
| Hard standard 1 | Accepted. | Same as F-1. |
| Hard standard 2 | Fixed. | `WebhookSender` and `EmailSender` ports live beside their adapters. `BreakingChangeNotifier` injects the ports. |
| Hard standard 3 | Fixed. | `NotificationRuleEnabledRequest` is removed. The controller and the service use `NotificationRuleRequest` on patch. `NotificationRuleControllerIT.patchAcceptsOnlyTheEnabledField` passes. |
| Hard standard 4 | Fixed. | The domain type is `DeliveryAttempt`, the term used in `services/contract/CONTEXT.md:36`. |
| Publish delivery | Added after review. | `ContractService.publishInTx` schedules delivery after commit for a breaking version. `ContractServiceTest` and `NotificationRuleControllerIT.breakingPublishSendsNotificationWithoutACheckId` pass. |

`./gradlew :services:contract:test --rerun-tasks --console=plain` and `./gradlew build` return `BUILD SUCCESSFUL`. All contract tests pass, including the new tenant-key, patch, and publish tests. The Testcontainers left by the run were removed. No process started for this work remains.
