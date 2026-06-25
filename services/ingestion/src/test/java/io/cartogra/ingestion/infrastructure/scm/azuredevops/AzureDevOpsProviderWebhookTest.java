package io.cartogra.ingestion.infrastructure.scm.azuredevops;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AzureDevOpsProviderWebhookTest {

    private final AzureDevOpsProvider provider = new AzureDevOpsProvider();

    private static String basic(String credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void verifyWebhookSignature_acceptsCorrectBasicAuth() {
        String secret = "hookuser:hookpass";
        Map<String, String> headers = Map.of("Authorization", basic(secret));
        assertThat(provider.verifyWebhookSignature(new byte[0], headers, secret)).isTrue();
    }

    @Test
    void verifyWebhookSignature_rejectsWrongBasicAuth() {
        String secret = "hookuser:hookpass";
        Map<String, String> headers = Map.of("Authorization", basic("hookuser:wrong"));
        assertThat(provider.verifyWebhookSignature(new byte[0], headers, secret)).isFalse();
    }

    @Test
    void verifyWebhookSignature_rejectsMissingAuthWhenSecretConfigured() {
        assertThat(provider.verifyWebhookSignature(new byte[0], Map.of(), "hookuser:hookpass")).isFalse();
    }

    @Test
    void verifyWebhookSignature_acceptsWhenNoSecretConfigured() {
        assertThat(provider.verifyWebhookSignature(new byte[0], Map.of(), null)).isTrue();
    }

    @Test
    void isRelevantWebhookEvent_gitPushIsRelevant() {
        String body = "{\"eventType\":\"git.push\",\"resource\":{}}";
        assertThat(provider.isRelevantWebhookEvent(Map.of(), body)).isTrue();
    }

    @Test
    void isRelevantWebhookEvent_otherEventIsIgnored() {
        String body = "{\"eventType\":\"build.complete\"}";
        assertThat(provider.isRelevantWebhookEvent(Map.of(), body)).isFalse();
    }
}
