package io.cartogra.ingestion.domain;

import io.cartogra.ingestion.repository.ScmConnectionRepository;
import io.cartogra.ingestion.infrastructure.scm.ScmProvider;
import io.cartogra.ingestion.repository.ScmWebhookRepository;
import io.cartogra.ingestion.domain.exception.WebhookConnectionNotFoundException;
import io.cartogra.ingestion.domain.exception.WebhookSignatureInvalidException;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandProducer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookServiceTest {

    private final ScmWebhookRepository webhookRepository = mock(ScmWebhookRepository.class);
    private final ScmConnectionRepository connectionRepository = mock(ScmConnectionRepository.class);
    private final SyncCommandProducer producer = mock(SyncCommandProducer.class);
    private final ScmProvider provider = mock(ScmProvider.class);

    private WebhookService service() {
        when(provider.providerType()).thenReturn("github");
        return new WebhookService(List.of(provider), webhookRepository, connectionRepository, producer);
    }

    private static ScmWebhook webhook(UUID tenantId, UUID connectionId) {
        Instant now = Instant.now();
        return new ScmWebhook(UUID.randomUUID(), tenantId, connectionId, "github",
                "ext", "https://example/hook", "secret", List.of("push"), "active",
                null, null, now, now, null);
    }

    private static ScmConnection connection(UUID tenantId, UUID connectionId) {
        Instant now = Instant.now();
        return new ScmConnection(connectionId, tenantId, "github", "{}",
                false, 15, null, null, null, true, now, now, null);
    }

    @Test
    void process_unknownProvider_throwsNotFound() {
        WebhookService service = service();
        assertThatThrownBy(() -> service.process("bitbucket", UUID.randomUUID(), new byte[0], Map.of()))
                .isInstanceOf(WebhookConnectionNotFoundException.class);
    }

    @Test
    void process_validPushPublishesSyncCommand() {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        ScmConnection conn = connection(tenantId, connectionId);
        when(webhookRepository.findByConnectionId(connectionId)).thenReturn(Optional.of(webhook(tenantId, connectionId)));
        when(connectionRepository.findById(tenantId, connectionId)).thenReturn(Optional.of(conn));
        when(provider.verifyWebhookSignature(any(), any(), eq("secret"))).thenReturn(true);
        when(provider.isRelevantWebhookEvent(any(), any())).thenReturn(true);

        service().process("github", connectionId, "{}".getBytes(), Map.of());

        verify(producer).publish(conn);
    }

    @Test
    void process_invalidSignature_throwsAndDoesNotPublish() {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        when(webhookRepository.findByConnectionId(connectionId)).thenReturn(Optional.of(webhook(tenantId, connectionId)));
        when(connectionRepository.findById(tenantId, connectionId)).thenReturn(Optional.of(connection(tenantId, connectionId)));
        when(provider.verifyWebhookSignature(any(), any(), any())).thenReturn(false);

        WebhookService service = service();
        assertThatThrownBy(() -> service.process("github", connectionId, "{}".getBytes(), Map.of()))
                .isInstanceOf(WebhookSignatureInvalidException.class);
        verify(producer, never()).publish(any());
    }

    @Test
    void process_irrelevantEvent_doesNotPublish() {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        when(webhookRepository.findByConnectionId(connectionId)).thenReturn(Optional.of(webhook(tenantId, connectionId)));
        when(connectionRepository.findById(tenantId, connectionId)).thenReturn(Optional.of(connection(tenantId, connectionId)));
        when(provider.verifyWebhookSignature(any(), any(), any())).thenReturn(true);
        when(provider.isRelevantWebhookEvent(any(), any())).thenReturn(false);

        service().process("github", connectionId, "{}".getBytes(), Map.of());

        verify(producer, never()).publish(any());
    }

    @Test
    void process_webhookDisabledConnection_throwsNotFound() {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        Instant now = Instant.now();
        ScmConnection disabled = new ScmConnection(connectionId, tenantId, "github", "{}",
                false, 15, null, null, null, false, now, now, null);
        when(webhookRepository.findByConnectionId(connectionId)).thenReturn(Optional.of(webhook(tenantId, connectionId)));
        when(connectionRepository.findById(tenantId, connectionId)).thenReturn(Optional.of(disabled));

        WebhookService service = service();
        assertThatThrownBy(() -> service.process("github", connectionId, "{}".getBytes(), Map.of()))
                .isInstanceOf(WebhookConnectionNotFoundException.class);
    }
}
