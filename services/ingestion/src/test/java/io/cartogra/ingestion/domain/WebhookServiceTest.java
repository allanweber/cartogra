package io.cartogra.ingestion.domain;

import io.cartogra.ingestion.domain.exception.WebhookConnectionNotFoundException;
import io.cartogra.ingestion.domain.exception.WebhookSignatureInvalidException;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandProducer;
import io.cartogra.ingestion.infrastructure.scm.ScmProvider;
import io.cartogra.ingestion.repository.ScmConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    ScmProvider githubProvider;
    @Mock
    ScmConnectionRepository connectionRepository;
    @Mock
    SyncCommandProducer syncCommandProducer;

    WebhookService service;

    private static final UUID CONN_ID = UUID.randomUUID();
    private static final byte[] BODY = "{}".getBytes();
    private static final Map<String, String> HEADERS = Map.of();

    @BeforeEach
    void setUp() {
        lenient().when(githubProvider.providerType()).thenReturn("github");
        service = new WebhookService(
                List.of(githubProvider),
                connectionRepository,
                syncCommandProducer,
                new ObjectMapper());
    }

    private ScmConnection connection(String provider) {
        return new ScmConnection(CONN_ID, UUID.randomUUID(), provider, "{\"webhookSecret\":\"s3cr3t\"}",
                false, 15, null, null, null, null, true,
                Instant.now(), Instant.now(), null);
    }

    @Test
    void rejectsMismatchedProviderBeforeVerifyingSignature() {
        when(connectionRepository.findByIdForWebhook(CONN_ID))
                .thenReturn(Optional.of(connection("azuredevops")));

        assertThatThrownBy(() -> service.process("github", CONN_ID, BODY, HEADERS))
                .isInstanceOf(WebhookConnectionNotFoundException.class);

        verify(githubProvider, never()).verifyWebhookSignature(any(), any(), any());
        verify(syncCommandProducer, never()).publish(any());
    }

    @Test
    void proceedsWhenProviderMatchesConnection() {
        ScmConnection conn = connection("github");
        when(connectionRepository.findByIdForWebhook(CONN_ID)).thenReturn(Optional.of(conn));
        when(githubProvider.verifyWebhookSignature(any(), any(), any())).thenReturn(true);
        when(githubProvider.isRelevantWebhookEvent(any(), any())).thenReturn(true);

        service.process("github", CONN_ID, BODY, HEADERS);

        verify(githubProvider).verifyWebhookSignature(any(), any(), any());
        verify(syncCommandProducer).publish(conn);
    }

    @Test
    void matchingProviderButInvalidSignatureThrows() {
        when(connectionRepository.findByIdForWebhook(CONN_ID))
                .thenReturn(Optional.of(connection("github")));
        when(githubProvider.verifyWebhookSignature(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.process("github", CONN_ID, BODY, HEADERS))
                .isInstanceOf(WebhookSignatureInvalidException.class);
    }
}
