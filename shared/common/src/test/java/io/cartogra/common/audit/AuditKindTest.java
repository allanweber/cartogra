package io.cartogra.common.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuditKindTest {

    @Test
    void settingsKindsAreHiddenFromGuests() {
        assertThat(AuditKind.guestVisibleEntityTypes())
                .containsExactlyInAnyOrder("service", "team", "dependency", "drift", "scm_connection")
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
}
