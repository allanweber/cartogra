package io.cartogra.ingestion.infrastructure.scm.github;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubProviderWebhookTest {

    private final GitHubProvider provider = new GitHubProvider();

    private static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void verifyWebhookSignature_acceptsCorrectHmac() {
        byte[] body = "{\"ref\":\"refs/heads/main\"}".getBytes(StandardCharsets.UTF_8);
        String secret = "topsecret";
        Map<String, String> headers = Map.of("X-Hub-Signature-256", sign(secret, body));

        assertThat(provider.verifyWebhookSignature(body, headers, secret)).isTrue();
    }

    @Test
    void verifyWebhookSignature_rejectsTamperedBody() {
        byte[] body = "{\"ref\":\"refs/heads/main\"}".getBytes(StandardCharsets.UTF_8);
        String secret = "topsecret";
        String signature = sign(secret, body);
        byte[] tampered = "{\"ref\":\"refs/heads/evil\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(provider.verifyWebhookSignature(tampered, Map.of("X-Hub-Signature-256", signature), secret))
                .isFalse();
    }

    @Test
    void verifyWebhookSignature_rejectsMissingSignatureWhenSecretConfigured() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(provider.verifyWebhookSignature(body, Map.of(), "topsecret")).isFalse();
    }

    @Test
    void verifyWebhookSignature_acceptsWhenNoSecretConfigured() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(provider.verifyWebhookSignature(body, Map.of(), null)).isTrue();
        assertThat(provider.verifyWebhookSignature(body, Map.of(), "")).isTrue();
    }

    @Test
    void isRelevantWebhookEvent_pushIsRelevant() {
        assertThat(provider.isRelevantWebhookEvent(Map.of("X-GitHub-Event", "push"), "{}")).isTrue();
    }

    @Test
    void isRelevantWebhookEvent_pingIsIgnored() {
        assertThat(provider.isRelevantWebhookEvent(Map.of("X-GitHub-Event", "ping"), "{}")).isFalse();
    }

    @Test
    void isRelevantWebhookEvent_missingHeaderIsIgnored() {
        assertThat(provider.isRelevantWebhookEvent(Map.of(), "{}")).isFalse();
    }
}
