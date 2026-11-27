package io.cartogra.common.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuditKindTest {

    @Test
    void settingsKindsAreHiddenFromGuests() {
        assertThat(AuditKind.guestVisibleEntityTypes())
                .containsExactlyInAnyOrder("service", "team", "dependency", "drift", "scm_connection", "contract")
                .doesNotContain("user", "invitation", "oidc_config", "cluster");
    }

    @Test
    void unknownEntityTypeHasNoKind() {
        assertThat(AuditKind.fromEntityType("future_kind")).isEqualTo(Optional.empty());
        assertThat(AuditKind.fromEntityType("team")).contains(AuditKind.TEAM);
    }

    @Test
    void guestPayloadKeepsOnlyDeclaredFieldsAtTopLevelAndInsideChanges() {
        Map<String, Object> payload = Map.of(
                "name", "payments",
                "userId", "u-1",
                "changes", Map.of(
                        "name", Map.of("from", "a", "to", "payments"),
                        "ownerEmail", Map.of("from", "a@x", "to", "b@x")));

        assertThat(AuditKind.TEAM.guestPayload(payload)).isEqualTo(Map.of(
                "name", "payments",
                "changes", Map.of("name", Map.of("from", "a", "to", "payments"))));
    }

    @Test
    void syncErrorTextNeverReachesGuests() {
        Map<String, Object> payload = Map.of("provider", "github", "error", "401 from https://ghe.internal/api");

        assertThat(AuditKind.SCM_CONNECTION.guestPayload(payload)).isEqualTo(Map.of("provider", "github"));
    }

    @Test
    void hiddenKindGivesGuestsAnEmptyPayload() {
        assertThat(AuditKind.USER.guestPayload(Map.of("email", "a@x", "role", "ADMIN"))).isEmpty();
    }

    @Test
    void apiKeyIsHiddenFromGuests() {
        assertThat(AuditKind.guestVisibleEntityTypes()).doesNotContain("api_key");
        assertThat(AuditKind.fromEntityType("api_key")).contains(AuditKind.API_KEY);
        assertThat(AuditKind.API_KEY.guestPayload(Map.of("name", "ci", "prefix", "cgk_ab"))).isEmpty();
    }

    @Test
    void notificationRuleIsHiddenFromGuests() {
        Map<String, Object> payload = Map.of(
                "ruleId", "r-1",
                "action", "disabled",
                "slackSet", true,
                "teamsSet", true,
                "emailCount", 3,
                "slackWebhookUrl", "https://hooks.slack.test/a/b/c",
                "teamsWebhookUrl", "https://hooks.teams.test/a/b/c",
                "emailAddresses", "a@x");

        assertThat(AuditKind.guestVisibleEntityTypes()).doesNotContain("notification_rule");
        assertThat(AuditKind.fromEntityType("notification_rule")).contains(AuditKind.NOTIFICATION_RULE);
        assertThat(AuditKind.NOTIFICATION_RULE.guestPayload(payload)).isEmpty();
    }
}
