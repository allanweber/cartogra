package io.cartogra.common.audit;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuditPayloadTest {

    record Connection(String name, String token, String passwordHash) {}

    private static final Set<String> ALLOWLIST = Set.of("name");
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void fieldsKeepsOnlyAllowlistedFields() {
        var payload = AuditPayload.fields(mapper, new Connection("gh", "ghp_secret", "$2a$hash"), ALLOWLIST);

        assertThat(payload).containsExactly(Map.entry("name", "gh"));
        assertThat(payload.toString()).doesNotContain("ghp_secret", "$2a$hash");
    }

    @Test
    void changesNeverIncludesSecretFieldsEvenWhenTheyChange() {
        var payload = AuditPayload.changes(mapper,
                new Connection("gh", "old-token", "old-hash"),
                new Connection("github", "new-token", "new-hash"), ALLOWLIST);

        assertThat(payload).containsOnlyKeys("changes");
        assertThat(payload.toString()).doesNotContain("token", "hash");
        assertThat(payload.get("changes")).isEqualTo(Map.of("name", Map.of("from", "gh", "to", "github")));
    }

    @Test
    void changesIsEmptyWhenOnlySecretFieldsChange() {
        var payload = AuditPayload.changes(mapper,
                new Connection("gh", "old-token", "h"), new Connection("gh", "new-token", "h"), ALLOWLIST);

        assertThat(payload).isEmpty();
    }

    @Test
    void systemActorClearsActorId() {
        var event = AuditEvent.of(java.util.UUID.randomUUID(), AuditKind.SERVICE, java.util.UUID.randomUUID(),
                "created", io.cartogra.common.identity.Actor.SYSTEM, Map.of());

        assertThat(event.actorKind()).isEqualTo(ActorKind.SYSTEM);
        assertThat(event.actorId()).isNull();
    }
}
