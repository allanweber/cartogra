package io.cartogra.ingestion.domain;

import io.cartogra.ingestion.api.dto.ScmConnectionRequest;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandProducer;
import io.cartogra.ingestion.infrastructure.registry.RegistryPlanLimitClient;
import io.cartogra.ingestion.repository.ScmConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    ScmConnectionService service;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CONN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ScmConnectionService(repository, syncCommandProducer, planLimitClient);
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
        assertThat(captor.getValue().config()).contains("\"token\":\"ghp_new\"");
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
        assertThat(result.config()).contains("\"webhookSecret\":\"whsec_123\"");
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
}
