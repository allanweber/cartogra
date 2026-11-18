package io.cartogra.ingestion.infrastructure.scm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class ScmProviderDefaultsTest {

    private final ScmProvider provider = mock(ScmProvider.class, CALLS_REAL_METHODS);

    @Test
    void defaultWebhookVerificationRejectsEvenWithASecret() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(provider.verifyWebhookSignature(body, Map.of(), "secret")).isFalse();
        assertThat(provider.verifyWebhookSignature(body, Map.of(), null)).isFalse();
    }
}
