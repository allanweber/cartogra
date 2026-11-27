# Notifications on breaking contract checks
Issue: #137  ·  Branch: `feat/5.7-notifications`

## Linked issue
Issue #137, `[5.7] Slack notification on breaking checks`, is open in `allanweber/cartogra`. The user confirmed this issue.

## Branch name
Use `feat/5.7-notifications`. This follows the repo branch strategy `feat/<name>` and covers all three channels.

## Problem
The CI check tells a developer that a spec change breaks consumers. No configured channel receives that result. The admin has no way to set a destination, and Settings → Notifications still shows a placeholder.

The issue says Slack only and forbids email. The user chose Slack, Teams, and email for this story. This plan follows the user decision and updates the roadmap and context map.

## Solution
Contract stores one notification rule per tenant and optional Service scope. A rule holds one Slack URL, one Teams URL, and a list of email addresses. On a breaking check, Contract returns the check result first. A virtual thread then sends the destinations in order and writes one delivery attempt row per try. The settings tab replaces the placeholder with rules, recent attempts, and a test button.

## User stories
1. As an admin, I want to set a Slack webhook for the whole tenant, so that every breaking check reaches one channel.
2. As an admin, I want to set a webhook for one Service, so that a team receives only its own breaking checks.
3. As an admin, I want a Service rule and a tenant-wide rule to both send, so that dedicated and general destinations both receive the message.
4. As an admin, I want one rule with Slack, Teams, and email destinations, so that I can manage one scope in one place.
5. As an admin, I want to pause a rule, so that I can stop delivery without losing its settings.
6. As an admin, I want a new rule enabled at once, so that the next breaking check uses it.
7. As an admin, I want a masked read of a webhook, so that the secret does not leave the backend.
8. As an admin, I want a masked read of email addresses, so that PII does not leave the backend.
9. As an admin, I want to replace destinations without reading them back, so that the form stays simple.
10. As a developer, I want the check response before delivery, so that CI does not wait for Slack or email.
11. As a developer, I want a compatible check to send nothing, so that only real breaks create messages.
12. As a developer, I want a summary and a link in the message, so that I can see the changes and open the contract.
13. As an operations engineer, I want three tries with backoff, so that a short provider outage does not lose the message.
14. As an operations engineer, I want one log row per try, so that I can see which try failed and why.
15. As an operations engineer, I want a pending row before each call, so that an in-flight crash stays visible.
16. As an admin, I want the latest 50 attempts, so that I can find a recent delivery failure.
17. As an admin, I want a test button for a saved rule, so that I can verify all destinations.
18. As an admin, I want a disabled rule to refuse a test, so that a paused rule stays paused.
19. As an admin, I want a 200 response with attempt outcomes from a test, so that the UI shows each failure.
20. As an auditor, I want rule changes in the audit stream, so that I can see who changed a scope.
21. As an auditor, I want no webhook or email address in audit data, so that audit does not store secrets or PII.
22. As a member, I want to change rules, so that I can manage the team's destinations.
23. As a viewer or guest, I want writes denied, so that I cannot change delivery settings.
24. As a developer, I want the OpenAPI document to match the API, so that generated clients stay correct.
25. As a deployer, I want the Resend key in Contract configuration, so that direct email delivery works in every environment.

## Business rules
- **BR-1**: Contract owns `notification_rules` and `notification_log` in the `contract` schema. Each table has a UUID primary key and `tenant_id UUID NOT NULL`. Both tables have forced row-level security. Rules use `deleted_at`; the log has no soft delete.
- **BR-2**: A rule has one tenant and an optional Service id. A rule with no Service id is tenant-wide. An active rule is unique per `(tenant_id, service_id)`, and a null Service id is a distinct scope. A deleted rule does not block a new rule. A breaking check matches the tenant-wide rule and the rule for its Service. A disabled rule sends nothing on a check.
- **BR-3**: A rule has at most one Slack URL, one Teams URL, and 20 email addresses. At least one destination is required. A webhook URL uses `https`; `http` is allowed only for `localhost` or `127.0.0.1`. Each email address is valid and at most 255 characters. An invalid rule is rejected before it is saved.
- **BR-4**: API responses mask webhooks and email addresses. A webhook response shows the host and path prefix plus a mask. An email response shows the first character and domain plus a mask. A write replaces the saved value. The frontend shows the masked value as received. No response, log, or audit event carries a raw destination.
- **BR-5**: Rule reads require an authenticated user. Rule writes require the existing `CAN_WRITE` expression. Guests and viewers get 403 on writes. The check API key cannot manage rules.
- **BR-6**: Every rule create, update, enable, disable, and delete writes one `audit.recorded` event. The payload has rule id, action, Service filter, enabled state, a Boolean per webhook channel, and the email count. It has no webhook value and no email address.
- **BR-7**: One log row describes one delivery try. The sender writes a pending row before the HTTP call and updates it after the call. Each row has try number, wait in milliseconds, channel, masked destination hint, HTTP status or network error, and outcome. A test row has no check id.
- **BR-8**: The message names the Service, the breaking result, the baseline version, each classified change, the Affected Consumers, and the contract link. A compatible check produces no message. A test message uses the same shape with a test label.
- **BR-9**: Each destination gets at most three tries. Try 1 has no wait. Try 2 waits 1 s and try 3 waits 2 s. The sender retries 429, 5xx, and network errors. It does not retry other 4xx responses. Destinations are sent in this order: Slack, Teams, then each email address.
- **BR-10**: Slack and Teams receive the same simple text payload. Email is sent by Contract directly to Resend with the same summary. The Resend key is set only in Contract configuration for this story. A provider failure is logged and does not change the check result.
- **BR-11**: `POST /contracts/check` returns before delivery starts. Contract starts one JDK virtual thread for the breaking result. A compatible result starts no thread. A notifier failure does not change the check response.
- **BR-12**: `POST /notification-rules/{id}/test` sends to every saved destination of a saved rule. It refuses a disabled rule with 409 `VALIDATION_ERROR`. It returns 200 with the attempt rows and shows each failed destination. It sends nothing to other rules.
- **BR-13**: `GET /notification-rules/{id}/attempts` returns the latest 50 rows, newest first. It uses the shared `PageResult` shape with zero offset and a limit of 50. It requires an authenticated user of the tenant.
- **BR-14**: `docs/api/contract.openapi.yaml` documents the rule, attempt, and test endpoints. It uses cookie and bearer security. It states that a check creates no audit event and no Kafka event. `ContractEnvelopeContractTest` validates each response.
- **BR-15**: Settings → Notifications replaces the placeholder with rule management, the latest attempts, and a test button. The frontend does not mask or unmask values. It shows the masked values from the backend.
- **BR-16**: The roadmap and context map describe Slack, Teams, and email for 5.7. They no longer say that 5.7 has no email channel. They keep the rule that no Kafka topic carries a check result.
- **BR-17**: Contract reads `RESEND_API_KEY` and the from address from configuration. Docker Compose and Helm pass these values to Contract. `helm lint` passes. No Secret YAML is committed.

## Scenarios
### S-1: Tenant rows stay isolated (BR-1)
```gherkin
Given two tenants each have one notification rule
When a query runs under the first tenant
Then only the first tenant's rule and log rows are visible
```

### S-2: One rule per scope (BR-2)
```gherkin
Given an active tenant-wide rule exists
When an admin creates a second tenant-wide rule
Then the API returns 409 CONFLICT and the first rule stays active
```

### S-3: Both matching rules send (BR-2)
```gherkin
Given a tenant-wide rule and a rule for ledger-service are enabled
When a breaking check for ledger-service completes
Then the notifier sends to the destinations of both rules
```

### S-4: A disabled rule sends nothing (BR-2)
```gherkin
Given the ledger-service rule is disabled
When a breaking check for ledger-service completes
Then the notifier sends no message for that rule
```

### S-5: A rule with all destinations is saved (BR-3)
```gherkin
Given an admin has one Slack URL, one Teams URL, and two email addresses
When the admin creates one enabled rule for ledger-service
Then the rule is stored with those destinations and enabled is true
```

### S-6: An invalid rule is rejected (BR-3)
```gherkin
Given an admin submits a rule with no destination, or an http webhook on a remote host, or 21 email addresses
When the API validates the rule
Then the API returns 400 VALIDATION_ERROR and stores no rule
```

### S-7: Destinations are masked in responses (BR-4)
```gherkin
Given a rule has a Slack URL, a Teams URL, and two email addresses
When an admin reads the rule
Then the response has masked values and no raw webhook or email address
```

### S-8: A rule change never exposes a destination in audit (BR-4, BR-6)
```gherkin
Given an admin changes a webhook and the email list
When the audit event is written
Then the event has channel flags and email count only
```

### S-9: A viewer cannot write rules (BR-5)
```gherkin
Given a viewer is signed in
When the viewer posts a notification rule
Then the API returns 403 and stores no rule
```

### S-10: A toggle is audited (BR-6)
```gherkin
Given an enabled rule has one Teams webhook and three emails
When an admin disables the rule
Then one audit event records action disabled, teamsSet true, and emailCount 3
```

### S-11: Each try has a pending row and a final row (BR-7)
```gherkin
Given a Teams webhook returns 503 on try 1 and 200 on try 2
When the sender finishes
Then the log has two rows, each written as pending before its call, with final status and outcome
```

### S-12: The message has a summary and link (BR-8)
```gherkin
Given a breaking check has two changes and twelve Affected Consumers
When the message is composed
Then it contains the Service, baseline version, both changes, all consumers, and the contract link
```

### S-13: A retry then succeeds (BR-9)
```gherkin
Given a webhook returns 429 on try 1 and 200 on try 2
When the sender runs
Then try 2 waits 1 second and the final outcome is sent
```

### S-14: All tries fail (BR-9)
```gherkin
Given a webhook returns 500 on every try
When the sender runs
Then it makes three tries, records a failure, and does not retry again
```

### S-15: Slack and Teams use simple text (BR-10)
```gherkin
Given a rule has both webhooks
When the notifier sends the summary
Then each request body has one text field and no channel-specific block format
```

### S-16: Email goes directly to Resend (BR-10)
```gherkin
Given a rule has two email addresses
When the notifier sends the summary
Then Contract posts two messages to Resend with its own API key
```

### S-17: The check response comes first (BR-11)
```gherkin
Given a breaking check and one enabled webhook that waits
When CI posts the candidate spec
Then CI receives the check result before the webhook call finishes
```

### S-18: A compatible check sends nothing (BR-11)
```gherkin
Given one enabled tenant-wide rule
When a compatible check completes
Then no virtual thread starts and no log row is written
```

### S-19: A test sends to all destinations (BR-12)
```gherkin
Given an enabled rule has Slack, Teams, and two emails
When an admin posts to the test endpoint
Then each destination receives one test message and the response has four outcome entries
```

### S-20: A disabled rule refuses a test (BR-12)
```gherkin
Given a rule is disabled
When an admin posts to its test endpoint
Then the API returns 409 VALIDATION_ERROR and sends nothing
```

### S-21: The history shows 50 rows (BR-13)
```gherkin
Given one rule has 80 attempt rows
When the UI requests attempts
Then the response has the newest 50 rows in descending time order
```

### S-22: The OpenAPI document matches the API (BR-14)
```gherkin
Given the new rule endpoints are live
When ContractEnvelopeContractTest validates rule, attempt, and test responses
Then every response matches the OpenAPI schema
```

### S-23: The settings placeholder is gone (BR-15)
```gherkin
Given an admin opens Settings → Notifications
Then the page shows rules, attempts, and a test button, not "coming soon"
```

### S-24: The frontend shows masked data only (BR-4, BR-15)
```gherkin
Given the API returns masked webhooks and masked emails
When the settings page renders the rule
Then the page shows the masked values and applies no further mask
```

### S-25: The docs describe all three channels (BR-16)
```gherkin
Given the user chose Slack, Teams, and email for 5.7
When a reader opens the roadmap and context map
Then both describe all three channels and keep the no-topic rule
```

### S-26: Contract has Resend configuration (BR-17)
```gherkin
Given Docker Compose and Helm define the Contract Resend values
When helm lint runs
Then the chart passes and no Secret YAML is in the repository
```

## Data model
One migration, `V009__notifications.sql`, creates two tables in the `contract` schema. Existing data does not change. No column is added to `contract_checks`.

### notification_rules
| Field | Type | Constraint |
|---|---|---|
| `id` | `UUID` | Primary key, default `gen_random_uuid()` |
| `tenant_id` | `UUID` | `NOT NULL` |
| `service_id` | `UUID` | Nullable Service id reference; no foreign key to another context |
| `enabled` | `BOOLEAN` | `NOT NULL`, default `true` |
| `slack_webhook_url` | `TEXT` | Nullable, stored as plain text |
| `teams_webhook_url` | `TEXT` | Nullable, stored as plain text |
| `email_addresses` | `JSONB` | `NOT NULL`, JSON array of email strings, default `[]` |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL`, default `now()` |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL`, default `now()` |
| `deleted_at` | `TIMESTAMPTZ` | Nullable soft delete |

Index: `CREATE UNIQUE INDEX notification_rules_one_per_scope ON notification_rules (tenant_id, service_id) NULLS NOT DISTINCT WHERE deleted_at IS NULL`.

RLS: enable and force on the table. Add the tenant policy from `V007__contract_checks.sql`.

### notification_log
| Field | Type | Constraint |
|---|---|---|
| `id` | `UUID` | Primary key, default `gen_random_uuid()` |
| `tenant_id` | `UUID` | `NOT NULL` |
| `rule_id` | `UUID` | `NOT NULL`, reference to `notification_rules.id` in the same tenant |
| `check_id` | `UUID` | Nullable reference to `contract_checks.id`; null for a test |
| `attempt` | `INTEGER` | `NOT NULL`, values 1 to 3 |
| `channel` | `TEXT` | `NOT NULL`, values `slack`, `teams`, `email` |
| `destination_hint` | `TEXT` | Nullable masked hint; never a raw URL or address |
| `wait_ms` | `INTEGER` | `NOT NULL`, default 0 |
| `status_code` | `INTEGER` | Nullable HTTP status |
| `error_code` | `TEXT` | Nullable provider or network error code |
| `outcome` | `TEXT` | `NOT NULL`, values `pending`, `sent`, `failed` |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL`, default `now()` |
| `finished_at` | `TIMESTAMPTZ` | Nullable when pending |

Index: `CREATE INDEX notification_log_rule_created ON notification_log (tenant_id, rule_id, created_at DESC)`.

RLS: enable and force on the table. Add the tenant policy from `V007__contract_checks.sql`.

## Decisions
1. Use issue #137 and plan number 036. Use branch `feat/5.7-notifications`.
2. Send to Slack, Teams, and email. This overrides the issue's no-email line. Update the roadmap and context map in the same story.
3. Name the stored entity `Notification Rule`. Keep the planned table names `notification_rules` and `notification_log`.
4. A rule has one tenant and an optional Service id. Both the tenant-wide rule and the Service rule send when both exist.
5. Allow one active rule per scope. Use a partial unique index with `NULLS NOT DISTINCT`. A soft-deleted rule does not block a new rule.
6. A rule holds one Slack URL, one Teams URL, and up to 20 email addresses. At least one destination is required.
7. A new rule is enabled. An on/off switch can pause it later.
8. Store webhook URLs as plain text in `notification_rules`. RLS and database access control are the chosen protection. The user accepted this instead of app-level encryption.
9. Mask webhooks in API responses. The admin can replace a webhook but cannot read it again.
10. Mask email addresses in the backend API response. The frontend shows the received value and applies no mask. This keeps PII inside the backend boundary.
11. Rule writes use the existing `Roles.CAN_WRITE` expression. That expression also accepts the legacy `TEAM_OWNER` role.
12. Audit rule changes through the existing `AuditEventPort`. Add a `NOTIFICATION_RULE` kind that guests cannot see.
13. Audit channel flags and email count. Never write a webhook or email address to audit data.
14. Return the check response before delivery. Start one JDK virtual thread for a breaking result. Do not start one for a compatible result.
15. Send destinations in a fixed order: Slack, Teams, then each email address. Do not stop after the first failure.
16. Give every destination three tries. Wait 1 s before try 2 and 2 s before try 3. Retry 429, 5xx, and network errors. Do not retry other 4xx responses.
17. Send one simple text payload to Slack and Teams. Use the same summary for email.
18. Call Resend directly from Contract for email. Do not add a gateway endpoint or a second gateway sender.
19. Compose one summary with Service, baseline version, changes, Affected Consumers, and contract link.
20. Write a pending log row before each call and update it after the call. One row describes one try.
21. Show the latest 50 attempts in the UI. Use the shared `PageResult` shape for that list.
22. Add a test button for a saved rule. The test refuses a disabled rule and returns 200 with attempt outcomes. It never tests unsaved form values.
23. Replace the Settings → Notifications tab page. Use the existing tabs, cards, and dialogs pattern. Do not build a new drawer.
24. Do not add a Playwright e2e test. Prove delivery with a Java integration test against WireMock.
25. Do not add a Kafka topic. Do not send a check audit event. Rule changes still use `cartogra.platform.audit.recorded`.
26. Keep the existing gateway route and circuit breaker. The check endpoint keeps its `CI_CHECK` role.
27. Include the issue's definition of done in acceptance criteria: code review and security review.

## Test seams
- `ContractCheckServiceTest`: mock the notifier and assert one call for a breaking check and none for a compatible check.
- `ContractCheckIT`: existing integration seam for the full check path with 12 consumers.
- `NotificationRuleServiceTest`, `NotificationRuleControllerIT`: rule validation, masking, uniqueness, roles, and audit.
- `NotificationLogRepositoryIT`, `BreakingChangeNotifierTest`: log rows and destination order with mocked senders.
- `WebhookNotificationSenderIT`, `EmailNotificationSenderIT`: WireMock already exists in `services/contract`. Use a dynamic port and a stub server.
- `WriteAuthorizationIT`: existing sweep of write routes for guests and viewers. New write routes join the sweep automatically.
- `ContractEnvelopeContractTest`: validates responses against `docs/api/contract.openapi.yaml`.
- `RowLevelSecurityIT`: existing tenant isolation checks for all tenant tables in the `contract` schema.
- Frontend: `settings.notifications.test.tsx` and `NotificationAttemptsCard.test.tsx` mock `apiFetch` and `apiMutate`. Replace the placeholder assertion in `placeholder-pages.test.tsx`.
- No e2e seam. A browser cannot stub a backend-to-provider HTTP call.

## E2E coverage
No new Playwright spec. A browser cannot intercept a backend-to-provider call. Cover delivery with Java integration tests against WireMock. Cover the settings form with frontend unit tests. Keep the existing `pnpm test:e2e` run in the final checks; no new spec is needed for this story.

## Tasks
### T1: Add a hidden notification-rule audit kind
- Implements: BR-4, BR-6, S-8, S-10
- Module: `shared/common`
- Files: `shared/common/src/main/java/io/cartogra/common/audit/AuditKind.java`, `shared/common/src/test/java/io/cartogra/common/audit/AuditKindTest.java`
- Depends on: none
- Change: Add `NOTIFICATION_RULE` with an empty guest-visible field set.
- Test: `AuditKindTest.notificationRuleIsHiddenFromGuests` (unit)
- Done when: The new kind exists and the guest allowlist assertion passes.

### T2: Create the notification tables
- Implements: BR-1, S-1
- Module: `services/contract`
- Files: `services/contract/src/main/resources/db/migration/V009__notifications.sql`, `services/contract/src/test/java/io/cartogra/contract/RowLevelSecurityIT.java`
- Depends on: none
- Change: Create `notification_rules` and `notification_log` with forced RLS, tenant policies, and the two indexes from the data model.
- Test: `RowLevelSecurityIT.notificationTablesAreTenantIsolated` (integration)
- Done when: Flyway applies V009 and both tenant isolation checks pass.

### T3: Store notification rules
- Implements: BR-2, BR-3, S-2, S-5
- Module: `services/contract`
- Files: `services/contract/src/main/java/io/cartogra/contract/domain/NotificationRule.java`, `services/contract/src/main/java/io/cartogra/contract/repository/NotificationRuleRepository.java`, `services/contract/src/main/java/io/cartogra/contract/infrastructure/jdbc/JdbcNotificationRuleRepository.java`
- Depends on: T2
- Change: Add the rule record and tenant-scoped repository. Enforce the one-rule-per-scope index through the repository.
- Test: `NotificationRuleRepositoryIT.tenantScopedCrudAndSingleRulePerScope` (integration)
- Done when: The repository creates, updates, toggles, soft-deletes, and rejects a second active rule in one scope.

### T4: Manage rules through the API
- Implements: BR-3, BR-4, BR-5, BR-6, S-5, S-6, S-7, S-8, S-9, S-10
- Module: `services/contract`
- Files: `services/contract/src/main/java/io/cartogra/contract/api/NotificationRuleController.java`, `services/contract/src/main/java/io/cartogra/contract/api/dto/NotificationRuleRequest.java`, `services/contract/src/main/java/io/cartogra/contract/api/dto/NotificationRuleResponse.java`, `services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java`
- Depends on: T1, T3
- Change: Add CRUD and toggle endpoints with validation, masked responses, `CAN_WRITE` on writes, and secret-free audit events.
- Test: `NotificationRuleControllerIT.crudValidationMaskingRolesAndAudit` (integration)
- Done when: Valid rules save, invalid rules return 400, views are masked, viewers get 403, and audit payloads have no raw destination.

### T5: Store delivery attempts
- Implements: BR-7, S-11
- Module: `services/contract`
- Files: `services/contract/src/main/java/io/cartogra/contract/domain/NotificationLog.java`, `services/contract/src/main/java/io/cartogra/contract/repository/NotificationLogRepository.java`, `services/contract/src/main/java/io/cartogra/contract/infrastructure/jdbc/JdbcNotificationLogRepository.java`
- Depends on: T2
- Change: Add the attempt record and tenant-scoped repository with `insertPending` and `finish`.
- Test: `NotificationLogRepositoryIT.pendingRowIsUpdatedAfterTheCall` (integration)
- Done when: A pending row is visible before `finish` and has final status and `finished_at` after it.

### T6: Compose the breaking-change message
- Implements: BR-8, S-12
- Module: `services/contract`
- Files: `services/contract/src/main/java/io/cartogra/contract/domain/BreakingChangeMessage.java`, `services/contract/src/test/java/io/cartogra/contract/domain/BreakingChangeMessageTest.java`
- Depends on: none
- Change: Render the summary, changes, Affected Consumers, and contract link from a `CheckResult`.
- Test: `BreakingChangeMessageTest.rendersServiceChangesConsumersAndLink` (unit)
- Done when: The rendered text contains every required field and no raw spec content.

### T7: Send Slack and Teams webhooks
- Implements: BR-9, BR-10, S-13, S-14, S-15
- Module: `services/contract`
- Files: `services/contract/src/main/java/io/cartogra/contract/infrastructure/slack/WebhookNotificationSender.java`, `services/contract/src/test/java/io/cartogra/contract/infrastructure/slack/WebhookNotificationSenderIT.java`
- Depends on: T5
- Change: Add one text-payload sender for both webhook channels, with three tries and 1 s then 2 s waits.
- Test: `WebhookNotificationSenderIT.retriesBackoffAndWritesAttempts` (integration)
- Done when: Retry then success and retry exhaustion both match BR-9, with one log row per try.

### T8: Send email through Resend
- Implements: BR-9, BR-10, S-13, S-14, S-16
- Module: `services/contract`
- Files: `services/contract/src/main/java/io/cartogra/contract/infrastructure/email/ResendEmailSender.java`, `services/contract/src/main/java/io/cartogra/contract/config/NotificationEmailProperties.java`, `services/contract/src/main/resources/application.yml`, `services/contract/src/test/java/io/cartogra/contract/infrastructure/email/ResendEmailSenderIT.java`
- Depends on: T5
- Change: Add a direct Resend sender with the same retry policy and configuration properties for key and from address.
- Test: `ResendEmailSenderIT.retriesBackoffAndWritesAttempts` (integration)
- Done when: WireMock receives one message per address and the log shows the expected tries.

### T9: Orchestrate notification delivery
- Implements: BR-2, BR-7, BR-8, BR-9, BR-10, S-3, S-4, S-11, S-12, S-13, S-14, S-15, S-16
- Module: `services/contract`
- Files: `services/contract/src/main/java/io/cartogra/contract/domain/BreakingChangeNotifier.java`, `services/contract/src/test/java/io/cartogra/contract/domain/BreakingChangeNotifierTest.java`
- Depends on: T3, T5, T6, T7, T8
- Change: Load all matching rules in one query, skip disabled rules, and send destinations in the fixed order with pending log rows.
- Test: `BreakingChangeNotifierTest.matchesBothScopesAndSendsInOrder` (unit)
- Done when: Both matching rules send, a disabled rule sends nothing, and no query runs inside a destination loop.

### T10: Start delivery after a breaking check
- Implements: BR-11, S-17, S-18
- Module: `services/contract`
- Files: `services/contract/src/main/java/io/cartogra/contract/domain/ContractCheckService.java`, `services/contract/src/test/java/io/cartogra/contract/domain/ContractCheckServiceTest.java`
- Depends on: T9
- Change: Start a JDK virtual thread for the notifier only when the verdict is breaking. Do not change the check response or write a check audit event.
- Test: `ContractCheckServiceTest.schedulesNotifierOnlyForBreakingChecks` (unit)
- Done when: A breaking check schedules one thread, a compatible check schedules none, and notifier failure does not fail the test.

### T11: Add the test endpoint
- Implements: BR-12, S-19, S-20
- Module: `services/contract`
- Files: `services/contract/src/main/java/io/cartogra/contract/api/NotificationRuleController.java`, `services/contract/src/main/java/io/cartogra/contract/domain/NotificationRuleService.java`
- Depends on: T4, T9
- Change: Add `POST /notification-rules/{id}/test`. Refuse a disabled rule with 409 and return 200 with the attempt rows.
- Test: `NotificationRuleControllerIT.testSendsToAllDestinationsAndRefusesDisabledRule` (integration)
- Done when: An enabled rule sends to all destinations and a disabled rule gets 409 with no log rows.

### T12: Return the latest attempts
- Implements: BR-13, S-21
- Module: `services/contract`
- Files: `services/contract/src/main/java/io/cartogra/contract/api/NotificationRuleController.java`, `services/contract/src/main/java/io/cartogra/contract/repository/NotificationLogRepository.java`, `services/contract/src/main/java/io/cartogra/contract/infrastructure/jdbc/JdbcNotificationLogRepository.java`
- Depends on: T4, T5
- Change: Add `GET /notification-rules/{id}/attempts` with newest-first order and a 50-row limit in a shared `PageResult`.
- Test: `NotificationRuleControllerIT.attemptsReturnsNewestFiftyInPageResult` (integration)
- Done when: Eighty rows produce the newest 50 in descending time order.

### T13: Validate rule responses against the OpenAPI document
- Implements: BR-14, S-22
- Module: `services/contract`
- Files: `services/contract/src/test/java/io/cartogra/contract/ContractEnvelopeContractTest.java`, `services/contract/src/test/java/io/cartogra/contract/ContractHttp.java`
- Depends on: T4, T11, T12
- Change: Add request helpers and validation cases for rule, attempt, and test responses.
- Test: `ContractEnvelopeContractTest.ruleEndpointsMatchOpenApi` (integration)
- Done when: The new validation cases pass against the current OpenAPI document.

### T14: Document the API
- Implements: BR-14, S-22
- Module: `docs`
- Files: `docs/api/contract.openapi.yaml`
- Depends on: T13
- Change: Add rule, attempt, and test paths and schemas. Keep the check statement that no audit event or Kafka event is created.
- Test: `ContractEnvelopeContractTest.ruleEndpointsMatchOpenApi` (integration)
- Done when: All new response envelopes and error cases validate against the OpenAPI document.

### T15: Update the product docs
- Implements: BR-16, S-25
- Module: `docs`
- Files: `CONTEXT-MAP.md`, `docs/roadmap.md`
- Depends on: none
- Change: Describe Slack, Teams, and email for 5.7. Remove the no-email line. Keep the no-topic rule.
- Test: `docs consistency grep` (check): `rg -q 'Slack, Teams, and email' CONTEXT-MAP.md docs/roadmap.md` passes
- Done when: Both files describe all three channels and no line forbids email for this story.

### T16: Wire Resend configuration into deployments
- Implements: BR-17, S-26
- Module: `infra`
- Files: `infra/docker-compose/docker-compose.yml`, `infra/helm/cartogra/charts/contract/values.yaml`, `infra/helm/cartogra/README.md`, `infra/helm/cartogra/templates/NOTES.txt`
- Depends on: T8
- Change: Pass the Resend key and from address to Contract. Do not commit a Secret YAML. Document the values.
- Test: `helm lint infra/helm/cartogra` (check)
- Done when: The chart lints and local Compose passes the same two values to Contract.

### T17: Replace the settings placeholder
- Implements: BR-4, BR-15, S-7, S-23, S-24
- Module: `frontend`
- Files: `frontend/src/routes/_authenticated/settings.notifications.tsx`, `frontend/src/lib/contract-types.ts`, `frontend/src/components/NotificationRuleCard.tsx`, `frontend/src/test/routes/settings.notifications.test.tsx`, `frontend/src/test/routes/placeholder-pages.test.tsx`
- Depends on: T4
- Change: Use TanStack Query and mutations to list, create, update, toggle, and delete rules. Show backend-masked values only.
- Test: `settings.notifications.test.tsx.rendersRulesAndMasksValuesFromApi` (unit)
- Done when: The page replaces the placeholder, calls the rule paths, and shows no unmasked destination.

### T18: Show attempts and add the test button
- Implements: BR-12, BR-13, BR-15, S-19, S-20, S-21, S-24
- Module: `frontend`
- Files: `frontend/src/components/NotificationAttemptsCard.tsx`, `frontend/src/components/NotificationRuleCard.tsx`, `frontend/src/test/components/NotificationAttemptsCard.test.tsx`
- Depends on: T11, T12, T17
- Change: Show the latest 50 attempts and a test button. Disable the button for a paused rule and show attempt outcomes after a test.
- Test: `NotificationAttemptsCard.test.tsx.showsAttemptsAndTestOutcomes` (unit)
- Done when: Attempts render newest first and a test response shows each failed destination.

## Acceptance criteria
- [ ] AC-1: Plan 036 uses issue #137 and branch `feat/5.7-notifications`.
- [ ] AC-2: A breaking check sends to every enabled matching rule; a compatible check sends nothing.
- [ ] AC-3: Delivery starts after the check response on a JDK virtual thread.
- [ ] AC-4: Every destination gets at most three tries with waits of 1 s and 2 s.
- [ ] AC-5: Each try has a pending log row that is updated with status and outcome.
- [ ] AC-6: No API response, log row, or audit event has a raw webhook or email address.
- [ ] AC-7: Rule writes use `CAN_WRITE`; guests and viewers get 403.
- [ ] AC-8: The settings page replaces the placeholder and shows rules, attempts, and a test button.
- [ ] AC-9: The OpenAPI document validates all new endpoints.
- [ ] AC-10: The roadmap and context map describe Slack, Teams, and email for 5.7.
- [ ] AC-11: Contract sends email directly to Resend in Compose and Helm.
- [ ] AC-12: `./gradlew build` passes.
- [ ] AC-13: `cd frontend && pnpm lint && pnpm typecheck && pnpm test && pnpm build` passes.
- [ ] AC-14: `cd seed/acme-fintech && pnpm typecheck && pnpm test` passes.
- [ ] AC-15: `cd frontend && pnpm test:e2e` passes with no new spec for this story.
- [ ] AC-16: `/code-review` finds no open correctness finding.
- [ ] AC-17: `/security-review` finds no open finding above informational, or the PR records an accepted risk with its compensating control.

## Out of scope
- No Kafka topic for check results or notification delivery.
- No email sender in the gateway and no change to auth emails.
- No Teams or email test against a real provider.
- No app-level encryption for webhooks. The user chose plain storage with RLS.
- No new settings drawer. The existing tab page is used.
- No test from unsaved form values.
- No history beyond the latest 50 attempts.
- No changes to the in-app NotificationBell risk notifications.
- No changes to the Acme seed. The issue marks its Acme test as n/a.

## Notes
- `services/contract/CONTEXT.md` already has the resolved terms `Notification Rule` and `Delivery Attempt`.
- The next Contract migration before this story is `V009`.
- WireMock is already a test dependency of `services/contract`.
- `Roles.CAN_WRITE` also accepts the legacy `TEAM_OWNER` role (ADR-0025).
- The gateway route for `/api/v1/contracts/**` and its `contract` breaker do not change.
- The check API key keeps scope `ci:check` and cannot manage rules.
- An e2e run uses the database of the process on port 8080. Check that process before running `pnpm test:e2e`.

## Changes during execution
- 2026-11-26: Review F-1: `POST /notification-rules/{id}/test` keeps synchronous delivery. The database writes inside the destination loop on this request path are accepted (user decision). No code change.
- 2026-11-26: Review F-3: `frontend/e2e/guest-demo.spec.ts` activates a graph node with `focus()` and `Enter`. The original circle click fails when the graph puts the node outside the viewport. The user approved this change.
- 2026-11-26: Review F-2: `pnpm test:e2e` passes with 10 tests on the local stack. The gateway health check on port 8080 passed before the run. The first run with the original circle click failed, and the run with the approved step passed.
- 2026-11-26: Review F-4: `V010__notification_log_rule_tenant.sql` adds `UNIQUE (tenant_id, id)` on `notification_rules` and a composite foreign key from `notification_log`. `DeliveryAttemptRepositoryIT.attemptCannotNameARuleFromAnotherTenant` asserts that a rule from another tenant is rejected. No application code changes.
- 2026-11-26: Review standards: `WebhookSender` and `EmailSender` ports live beside their adapters, and `BreakingChangeNotifier` injects the ports. The second request record is removed; patch uses `NotificationRuleRequest`. The domain record `NotificationLog` is renamed to `DeliveryAttempt`. `NotificationRuleControllerIT.patchAcceptsOnlyTheEnabledField` covers the patch rules.
- 2026-11-27: A breaking publish also sends notifications. `ContractService.publishInTx` registers an after-commit callback and starts a virtual thread that calls `BreakingChangeNotifier.notifyBreaking` with a null check id. Manual publish and spec discovery use the same path. Compatible publishes and unchanged specs send nothing.
