package io.cartogra.common.identity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActorTest {

    @Test
    void apiKeyActorHasNoUserAndIsNotAdmin() {
        UUID keyId = UUID.randomUUID();

        Actor actor = Actor.apiKey(keyId);

        assertThat(actor.userId()).isNull();
        assertThat(actor.admin()).isFalse();
        assertThat(actor.apiKeyId()).isEqualTo(keyId);
    }

    @Test
    void twoArgumentActorsHaveNoApiKey() {
        UUID userId = UUID.randomUUID();

        assertThat(Actor.user(userId).apiKeyId()).isNull();
        assertThat(Actor.admin(userId).apiKeyId()).isNull();
        assertThat(Actor.SYSTEM.apiKeyId()).isNull();
    }
}
