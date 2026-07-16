package io.cartogra.ingestion.domain;

import io.cartogra.ingestion.api.dto.ScmConnectionRequest;
import io.cartogra.ingestion.infrastructure.k8s.CredentialEncryptor;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandProducer;
import io.cartogra.ingestion.infrastructure.registry.RegistryPlanLimitClient;
import io.cartogra.ingestion.infrastructure.validation.ScmApiBaseUrlValidator;
import io.cartogra.ingestion.repository.ScmConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScmConnectionServiceTest {

    @Mock
    ScmConnectionRepository repository;
    @Mock
    SyncCommandProducer syncCommandProducer;
    @Mock
    RegistryPlanLimitClient planLimitClient;
    @Mock
    CredentialEncryptor credentialEncryptor;
    @Mock
    ScmApiBaseUrlValidator scmApiBaseUrlValidator;

    ScmConnectionService service;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CONN_ID = UUID.randomUUID();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Reversible fake cipher: proves the service actually calls encrypt()/decrypt() on the
        // secret keys, without needing a real AES key/IV round-trip in a unit test.
        lenient().when(credentialEncryptor.encrypt(anyString()))
                .thenAnswer(inv -> "enc:" + inv.getArgument(0));
        lenient().when(credentialEncryptor.decrypt(anyString()))
                .thenAnswer(inv -> {
                    String s = inv.getArgument(0);
                    return s.startsWith("enc:") ? s.substring("enc:".length()) : s;
                });
        service = new ScmConnectionService(repository, syncCommandProducer, planLimitClient, credentialEncryptor,
                scmApiBaseUrlValidator);
    }

    @SuppressWarnings("unchecked")
    private static String secretValue(String configJson, String key) {
        Map<String, Object> parsed = MAPPER.readValue(configJson, Map.class);
        return (String) parsed.get(key);
    }

    /** Mirrors the fake cipher stubbed onto {@code credentialEncryptor} in setUp(), without
     * invoking the mock directly from an assertion. */
    private static String decryptForTest(String value) {
        return value.startsWith("enc:") ? value.substring("enc:".length()) : value;
    }

    private ScmConnection existing(String config) {
        return new ScmConnection(CONN_ID, TENANT, "github", config, true, 15,
                null, null, "FAILED", "GitHub API error listing repos: 401 UNAUTHORIZED",
                false, Instant.now(), Instant.now(), null);
    }

    @Test
    void updateWithoutTokenPreservesStoredToken() {
        when(repository.findById(TENANT, CONN_ID))
                .thenReturn(Optional.of(existing("{\"org\":\"acme\",\"token\":\"ghp_secret\"}")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScmConnectionRequest req = new ScmConnectionRequest(null, "{\"org\":\"acme-renamed\"}", null, null, null);
        service.update(TENANT, CONN_ID, req);

        ArgumentCaptor<ScmConnection> captor = ArgumentCaptor.forClass(ScmConnection.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().config()).contains("\"token\":\"ghp_secret\"");
        assertThat(captor.getValue().config()).contains("\"org\":\"acme-renamed\"");
    }

    @Test
    void updateWithNewTokenOverwritesStoredToken() {
        when(repository.findById(TENANT, CONN_ID))
                .thenReturn(Optional.of(existing("{\"org\":\"acme\",\"token\":\"ghp_old\"}")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScmConnectionRequest req = new ScmConnectionRequest(null, "{\"org\":\"acme\",\"token\":\"ghp_new\"}", null, null, null);
        service.update(TENANT, CONN_ID, req);

        ArgumentCaptor<ScmConnection> captor = ArgumentCaptor.forClass(ScmConnection.class);
        verify(repository).save(captor.capture());
        String storedToken = secretValue(captor.getValue().config(), "token");
        assertThat(storedToken).isNotEqualTo("ghp_new");
        assertThat(decryptForTest(storedToken)).isEqualTo("ghp_new");
    }

    @Test
    void updateWithConfigChangeTriggersImmediateSync() {
        when(repository.findById(TENANT, CONN_ID))
                .thenReturn(Optional.of(existing("{\"org\":\"acme\",\"token\":\"ghp_old\"}")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScmConnectionRequest req = new ScmConnectionRequest(null, "{\"org\":\"acme\",\"token\":\"ghp_new\"}", null, null, null);
        ScmConnection result = service.update(TENANT, CONN_ID, req);

        verify(syncCommandProducer).publish(result);
    }

    @Test
    void updateWithoutConfigChangeDoesNotTriggerSync() {
        when(repository.findById(TENANT, CONN_ID))
                .thenReturn(Optional.of(existing("{\"org\":\"acme\",\"token\":\"ghp_old\"}")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScmConnectionRequest req = new ScmConnectionRequest(null, null, null, 30, null);
        service.update(TENANT, CONN_ID, req);

        verifyNoInteractions(syncCommandProducer);
    }

    @Test
    void createWithWebhookEnabledAndNoSecretThrows() {
        lenient().when(planLimitClient.fetchLimits(TENANT)).thenReturn(Optional.empty());

        ScmConnectionRequest req = new ScmConnectionRequest("github", "{\"org\":\"acme\"}", null, null, true);

        assertThatThrownBy(() -> service.create(TENANT, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("webhookSecret");
    }

    @Test
    void createWithWebhookEnabledAndSecretSucceeds() {
        when(planLimitClient.fetchLimits(TENANT)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScmConnectionRequest req = new ScmConnectionRequest(
                "github", "{\"org\":\"acme\",\"webhookSecret\":\"whsec_123\"}", null, null, true);

        ScmConnection result = service.create(TENANT, req);

        assertThat(result.webhookEnabled()).isTrue();
        String storedSecret = secretValue(result.config(), "webhookSecret");
        assertThat(storedSecret).isNotEqualTo("whsec_123");
        assertThat(decryptForTest(storedSecret)).isEqualTo("whsec_123");
    }

    @Test
    void createEncryptsTokenBeforeSendingToRepository() {
        when(planLimitClient.fetchLimits(TENANT)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScmConnectionRequest req = new ScmConnectionRequest(
                "github", "{\"org\":\"acme\",\"token\":\"plaintext-value\"}", null, null, null);

        service.create(TENANT, req);

        ArgumentCaptor<ScmConnection> captor = ArgumentCaptor.forClass(ScmConnection.class);
        verify(repository).save(captor.capture());
        String storedToken = secretValue(captor.getValue().config(), "token");
        assertThat(storedToken).isNotEqualTo("plaintext-value");
        assertThat(decryptForTest(storedToken)).isEqualTo("plaintext-value");
    }

    @Test
    void createPropagatesEncryptorFailureInsteadOfPersistingPlaintext() {
        when(planLimitClient.fetchLimits(TENANT)).thenReturn(Optional.empty());
        when(credentialEncryptor.encrypt(anyString())).thenThrow(new RuntimeException("encryption key missing"));

        ScmConnectionRequest req = new ScmConnectionRequest(
                "github", "{\"org\":\"acme\",\"token\":\"plaintext-value\"}", null, null, null);

        assertThatThrownBy(() -> service.create(TENANT, req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("encryption key missing");

        verifyNoInteractions(repository);
    }

    @Test
    void createWithWebhookDisabledAndNoSecretSucceeds() {
        when(planLimitClient.fetchLimits(TENANT)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScmConnectionRequest req = new ScmConnectionRequest("github", "{\"org\":\"acme\"}", null, null, null);

        ScmConnection result = service.create(TENANT, req);

        assertThat(result.webhookEnabled()).isFalse();
    }

    @Test
    void updateEnablingWebhookWithoutSecretThrows() {
        when(repository.findById(TENANT, CONN_ID))
                .thenReturn(Optional.of(existing("{\"org\":\"acme\",\"token\":\"ghp_old\"}")));

        ScmConnectionRequest req = new ScmConnectionRequest(null, null, null, null, true);

        assertThatThrownBy(() -> service.update(TENANT, CONN_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("webhookSecret");
    }

    @Test
    void updateOnAlreadyWebhookEnabledConnectionChangingOnlyPollIntervalSucceeds() {
        ScmConnection existingWithWebhook = new ScmConnection(CONN_ID, TENANT, "github",
                "{\"org\":\"acme\",\"token\":\"ghp_old\",\"webhookSecret\":\"whsec_123\"}", true, 15,
                null, null, "FAILED", "GitHub API error listing repos: 401 UNAUTHORIZED",
                true, Instant.now(), Instant.now(), null);
        when(repository.findById(TENANT, CONN_ID)).thenReturn(Optional.of(existingWithWebhook));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScmConnectionRequest req = new ScmConnectionRequest(null, null, null, 30, null);
        ScmConnection result = service.update(TENANT, CONN_ID, req);

        assertThat(result.pollIntervalMinutes()).isEqualTo(30);
        assertThat(result.webhookEnabled()).isTrue();
    }

    // --- apiBaseUrl SSRF validation wiring ---

    @Test
    void createWithoutApiBaseUrlDoesNotTriggerValidation() {
        when(planLimitClient.fetchLimits(TENANT)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScmConnectionRequest req = new ScmConnectionRequest("github", "{\"org\":\"acme\"}", null, null, null);
        service.create(TENANT, req);

        verifyNoInteractions(scmApiBaseUrlValidator);
    }

    @Test
    void createWithDisallowedApiBaseUrlRejectsAndDoesNotPersist() {
        lenient().when(planLimitClient.fetchLimits(TENANT)).thenReturn(Optional.empty());
        doThrow(new IllegalArgumentException("apiBaseUrl resolves to a loopback or link-local address"))
                .when(scmApiBaseUrlValidator).validate("http://169.254.169.254/");

        ScmConnectionRequest req = new ScmConnectionRequest(
                "github", "{\"org\":\"acme\",\"apiBaseUrl\":\"http://169.254.169.254/\"}", null, null, null);

        assertThatThrownBy(() -> service.create(TENANT, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback or link-local");

        verifyNoInteractions(repository);
    }

    @Test
    void createWithLegitimateApiBaseUrlValidatesAndSucceeds() {
        when(planLimitClient.fetchLimits(TENANT)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScmConnectionRequest req = new ScmConnectionRequest(
                "github", "{\"org\":\"acme\",\"apiBaseUrl\":\"https://ghes.example-corp.com/api/v3\"}", null, null, null);

        ScmConnection result = service.create(TENANT, req);

        verify(scmApiBaseUrlValidator).validate("https://ghes.example-corp.com/api/v3");
        assertThat(result).isNotNull();
    }

    @Test
    void updateWithoutApiBaseUrlDoesNotTriggerValidation() {
        when(repository.findById(TENANT, CONN_ID))
                .thenReturn(Optional.of(existing("{\"org\":\"acme\",\"token\":\"ghp_old\"}")));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScmConnectionRequest req = new ScmConnectionRequest(null, null, null, 30, null);
        service.update(TENANT, CONN_ID, req);

        verifyNoInteractions(scmApiBaseUrlValidator);
    }

    @Test
    void updateWithDisallowedApiBaseUrlRejectsAndDoesNotPersist() {
        when(repository.findById(TENANT, CONN_ID))
                .thenReturn(Optional.of(existing("{\"org\":\"acme\",\"token\":\"ghp_old\"}")));
        doThrow(new IllegalArgumentException("apiBaseUrl resolves to a private address"))
                .when(scmApiBaseUrlValidator).validate("http://10.0.0.5/");

        ScmConnectionRequest req = new ScmConnectionRequest(
                null, "{\"org\":\"acme\",\"apiBaseUrl\":\"http://10.0.0.5/\"}", null, null, null);

        assertThatThrownBy(() -> service.update(TENANT, CONN_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private address");

        verify(repository, org.mockito.Mockito.never()).save(any());
    }
}
