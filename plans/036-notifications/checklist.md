# Checklist: Notifications on breaking contract checks
Plan: [plan.md](./plan.md)

## Tasks
- [x] T1 Add a hidden notification-rule audit kind: test `AuditKindTest.notificationRuleIsHiddenFromGuests` passes
- [x] T2 Create the notification tables: test `RowLevelSecurityIT.notificationTablesAreTenantIsolated` passes
- [x] T3 Store notification rules: test `NotificationRuleRepositoryIT.tenantScopedCrudAndSingleRulePerScope` passes
- [x] T4 Manage rules through the API: test `NotificationRuleControllerIT.crudValidationMaskingRolesAndAudit` passes
- [x] T5 Store delivery attempts: test `NotificationLogRepositoryIT.pendingRowIsUpdatedAfterTheCall` passes
- [x] T6 Compose the breaking-change message: test `BreakingChangeMessageTest.rendersServiceChangesConsumersAndLink` passes
- [x] T7 Send Slack and Teams webhooks: test `WebhookNotificationSenderIT.retriesBackoffAndWritesAttempts` passes
- [x] T8 Send email through Resend: test `ResendEmailSenderIT.retriesBackoffAndWritesAttempts` passes
- [x] T9 Orchestrate notification delivery: test `BreakingChangeNotifierTest.matchesBothScopesAndSendsInOrder` passes
- [x] T10 Start delivery after a breaking check: test `ContractCheckServiceTest.schedulesNotifierOnlyForBreakingChecks` passes
- [x] T11 Add the test endpoint: test `NotificationRuleControllerIT.testSendsToAllDestinationsAndRefusesDisabledRule` passes
- [x] T12 Return the latest attempts: test `NotificationRuleControllerIT.attemptsReturnsNewestFiftyInPageResult` passes
- [x] T13 Validate rule responses against the OpenAPI document: test `ContractEnvelopeContractTest.ruleEndpointsMatchOpenApi` passes
- [x] T14 Document the API: test `ContractEnvelopeContractTest.ruleEndpointsMatchOpenApi` passes
- [x] T15 Update the product docs: check `rg -q 'Slack, Teams, and email' CONTEXT-MAP.md docs/roadmap.md` passes
- [x] T16 Wire Resend configuration into deployments: check `helm lint infra/helm/cartogra` passes
- [x] T17 Replace the settings placeholder: test `settings.notifications.test.tsx.rendersRulesAndMasksValuesFromApi` passes
- [x] T18 Show attempts and add the test button: test `NotificationAttemptsCard.test.tsx.showsAttemptsAndTestOutcomes` passes

## Scenarios
- [x] S-1 Tenant rows stay isolated (BR-1): integration `RowLevelSecurityIT.notificationTablesAreTenantIsolated`
- [x] S-2 One rule per scope (BR-2): integration `NotificationRuleRepositoryIT.tenantScopedCrudAndSingleRulePerScope`
- [x] S-3 Both matching rules send (BR-2): unit `BreakingChangeNotifierTest.matchesBothScopesAndSendsInOrder`
- [x] S-4 A disabled rule sends nothing (BR-2): unit `BreakingChangeNotifierTest.matchesBothScopesAndSendsInOrder`
- [x] S-5 A rule with all destinations is saved (BR-3): integration `NotificationRuleControllerIT.crudValidationMaskingRolesAndAudit`
- [x] S-6 An invalid rule is rejected (BR-3): integration `NotificationRuleControllerIT.crudValidationMaskingRolesAndAudit`
- [x] S-7 Destinations are masked in responses (BR-4): integration `NotificationRuleControllerIT.crudValidationMaskingRolesAndAudit`
- [x] S-8 A rule change never exposes a destination in audit (BR-4, BR-6): unit `AuditKindTest.notificationRuleIsHiddenFromGuests`
- [x] S-9 A viewer cannot write rules (BR-5): integration `NotificationRuleControllerIT.crudValidationMaskingRolesAndAudit`
- [x] S-10 A toggle is audited (BR-6): integration `NotificationRuleControllerIT.crudValidationMaskingRolesAndAudit`
- [x] S-11 Each try has a pending row and a final row (BR-7): integration `NotificationLogRepositoryIT.pendingRowIsUpdatedAfterTheCall`
- [x] S-12 The message has a summary and link (BR-8): unit `BreakingChangeMessageTest.rendersServiceChangesConsumersAndLink`
- [x] S-13 A retry then succeeds (BR-9): integration `WebhookNotificationSenderIT.retriesBackoffAndWritesAttempts`
- [x] S-14 All tries fail (BR-9): integration `WebhookNotificationSenderIT.retriesBackoffAndWritesAttempts`
- [x] S-15 Slack and Teams use simple text (BR-10): integration `WebhookNotificationSenderIT.retriesBackoffAndWritesAttempts`
- [x] S-16 Email goes directly to Resend (BR-10): integration `ResendEmailSenderIT.retriesBackoffAndWritesAttempts`
- [x] S-17 The check response comes first (BR-11): unit `ContractCheckServiceTest.schedulesNotifierOnlyForBreakingChecks`
- [x] S-18 A compatible check sends nothing (BR-11): unit `ContractCheckServiceTest.schedulesNotifierOnlyForBreakingChecks`
- [x] S-19 A test sends to all destinations (BR-12): integration `NotificationRuleControllerIT.testSendsToAllDestinationsAndRefusesDisabledRule`
- [x] S-20 A disabled rule refuses a test (BR-12): integration `NotificationRuleControllerIT.testSendsToAllDestinationsAndRefusesDisabledRule`
- [x] S-21 The history shows 50 rows (BR-13): integration `NotificationRuleControllerIT.attemptsReturnsNewestFiftyInPageResult`
- [x] S-22 The OpenAPI document matches the API (BR-14): integration `ContractEnvelopeContractTest.ruleEndpointsMatchOpenApi`
- [x] S-23 The settings placeholder is gone (BR-15): unit `settings.notifications.test.tsx.rendersRulesAndMasksValuesFromApi`
- [x] S-24 The frontend shows masked data only (BR-4, BR-15): unit `settings.notifications.test.tsx.rendersRulesAndMasksValuesFromApi`
- [x] S-25 The docs describe all three channels (BR-16): check `rg -q 'Slack, Teams, and email' CONTEXT-MAP.md docs/roadmap.md`
- [x] S-26 Contract has Resend configuration (BR-17): check `helm lint infra/helm/cartogra`

## Business rules
- [x] BR-1: scenarios S-1 pass
- [x] BR-2: scenarios S-2, S-3, S-4 pass
- [x] BR-3: scenarios S-5, S-6 pass
- [x] BR-4: scenarios S-7, S-8, S-24 pass
- [x] BR-5: scenario S-9 passes
- [x] BR-6: scenarios S-8, S-10 pass
- [x] BR-7: scenario S-11 passes
- [x] BR-8: scenario S-12 passes
- [x] BR-9: scenarios S-13, S-14 pass
- [x] BR-10: scenarios S-15, S-16 pass
- [x] BR-11: scenarios S-17, S-18 pass
- [x] BR-12: scenarios S-19, S-20 pass
- [x] BR-13: scenario S-21 passes
- [x] BR-14: scenario S-22 passes
- [x] BR-15: scenarios S-23, S-24 pass
- [x] BR-16: scenario S-25 passes
- [x] BR-17: scenario S-26 passes

## Acceptance criteria
- [x] AC-1 Plan 036 uses issue #137 and branch `feat/5.7-notifications`
- [x] AC-2 A breaking check sends to every enabled matching rule; a compatible check sends nothing
- [x] AC-3 Delivery starts after the check response on a JDK virtual thread
- [x] AC-4 Every destination gets at most three tries with waits of 1 s and 2 s
- [x] AC-5 Each try has a pending log row that is updated with status and outcome
- [x] AC-6 No API response, log row, or audit event has a raw webhook or email address
- [x] AC-7 Rule writes use `CAN_WRITE`; guests and viewers get 403
- [x] AC-8 The settings page replaces the placeholder and shows rules, attempts, and a test button
- [x] AC-9 The OpenAPI document validates all new endpoints
- [x] AC-10 The roadmap and context map describe Slack, Teams, and email for 5.7
- [x] AC-11 Contract sends email directly to Resend in Compose and Helm
- [x] AC-12 `./gradlew build` passes
- [x] AC-13 `cd frontend && pnpm lint && pnpm typecheck && pnpm test && pnpm build` passes
- [x] AC-14 `cd seed/acme-fintech && pnpm typecheck && pnpm test` passes
- [x] AC-15 `cd frontend && pnpm test:e2e` passes with no new spec for this story: 10 tests pass on the local stack
- [x] AC-16 `/code-review` finds no open correctness finding
- [x] AC-17 `/security-review` finds no open finding above informational, or the PR records an accepted risk with its compensating control

## Verification
- [x] `./gradlew build` passes
- [x] `cd frontend && pnpm lint && pnpm typecheck && pnpm test && pnpm build` passes
- [x] `cd seed/acme-fintech && pnpm typecheck && pnpm test` passes
- [x] `cd frontend && pnpm test:e2e` passes: 10 tests pass after the gateway health check on port 8080
- [x] `helm lint infra/helm/cartogra` passes
- [x] `/code-review` and `/security-review` are done on the final diff
- [x] `./gradlew :services:contract:test --rerun-tasks --console=plain` passes after the review fixes, including the new tenant-key and patch tests
- [x] Review F-4 is fixed by `V010__notification_log_rule_tenant.sql`; the three hard standards findings 2, 3, and 4 are fixed
- [x] A breaking publish sends notifications after commit, with a null check id; manual publish and discovery share the path. `ContractServiceTest` and `NotificationRuleControllerIT.breakingPublishSendsNotificationWithoutACheckId` pass
