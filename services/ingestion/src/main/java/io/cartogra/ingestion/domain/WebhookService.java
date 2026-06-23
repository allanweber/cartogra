package io.cartogra.ingestion.domain;

import io.cartogra.ingestion.repository.ScmConnectionRepository;
import io.cartogra.ingestion.infrastructure.scm.ScmProvider;
import io.cartogra.ingestion.repository.ScmWebhookRepository;
import io.cartogra.ingestion.domain.exception.WebhookConnectionNotFoundException;
import io.cartogra.ingestion.domain.exception.WebhookSignatureInvalidException;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validates an inbound SCM webhook and, when relevant, publishes a sync command.
 * Secret and tenant come straight from {@code scm_webhooks} — no cross-service call.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final Map<String, ScmProvider> providers;
    private final ScmWebhookRepository webhookRepository;
    private final ScmConnectionRepository connectionRepository;
    private final SyncCommandProducer syncCommandProducer;

    public WebhookService(
            List<ScmProvider> providers,
            ScmWebhookRepository webhookRepository,
            ScmConnectionRepository connectionRepository,
            SyncCommandProducer syncCommandProducer) {
        this.providers = providers.stream()
                .collect(Collectors.toMap(ScmProvider::providerType, Function.identity()));
        this.webhookRepository = webhookRepository;
        this.connectionRepository = connectionRepository;
        this.syncCommandProducer = syncCommandProducer;
    }

    public void process(String providerType, UUID connectionId, byte[] rawBody, Map<String, String> headers) {
        ScmProvider provider = providers.get(providerType);
        if (provider == null) {
            throw new WebhookConnectionNotFoundException(providerType);
        }

        ScmWebhook webhook = webhookRepository.findByConnectionId(connectionId)
                .orElseThrow(() -> new WebhookConnectionNotFoundException(connectionId.toString()));

        ScmConnection connection = connectionRepository.findById(webhook.tenantId(), connectionId)
                .filter(ScmConnection::webhookEnabled)
                .orElseThrow(() -> new WebhookConnectionNotFoundException(connectionId.toString()));

        if (!provider.verifyWebhookSignature(rawBody, headers, webhook.webhookSecret())) {
            throw new WebhookSignatureInvalidException(connectionId);
        }

        if (!provider.isRelevantWebhookEvent(headers, new String(rawBody, StandardCharsets.UTF_8))) {
            log.debug("Ignoring non-actionable webhook event for connection={}", connectionId);
            return;
        }

        syncCommandProducer.publish(connection);
    }
}
