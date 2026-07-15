package io.cartogra.ingestion.domain;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.cartogra.ingestion.infrastructure.k8s.CredentialEncryptor;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandProducer;
import io.cartogra.ingestion.infrastructure.scm.ScmProvider;
import io.cartogra.ingestion.repository.ScmConnectionRepository;
import io.cartogra.ingestion.domain.exception.WebhookConnectionNotFoundException;
import io.cartogra.ingestion.domain.exception.WebhookSignatureInvalidException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final Map<String, ScmProvider> providers;
    private final ScmConnectionRepository connectionRepository;
    private final SyncCommandProducer syncCommandProducer;
    private final ObjectMapper objectMapper;
    private final CredentialEncryptor credentialEncryptor;

    public WebhookService(
            List<ScmProvider> providers,
            ScmConnectionRepository connectionRepository,
            SyncCommandProducer syncCommandProducer,
            ObjectMapper objectMapper,
            CredentialEncryptor credentialEncryptor) {
        this.providers = providers.stream()
                .collect(Collectors.toMap(ScmProvider::providerType, Function.identity()));
        this.connectionRepository = connectionRepository;
        this.syncCommandProducer = syncCommandProducer;
        this.objectMapper = objectMapper;
        this.credentialEncryptor = credentialEncryptor;
    }

    public void process(String providerType, UUID connectionId, byte[] rawBody, Map<String, String> headers) {
        ScmProvider provider = providers.get(providerType);
        if (provider == null) {
            throw new WebhookConnectionNotFoundException(providerType);
        }

        ScmConnection connection = connectionRepository.findByIdForWebhook(connectionId)
                .orElseThrow(() -> new WebhookConnectionNotFoundException(connectionId.toString()));

        if (!provider.providerType().equals(connection.provider())) {
            throw new WebhookConnectionNotFoundException(connectionId.toString());
        }

        String webhookSecret = extractWebhookSecret(connection.config());

        if (!provider.verifyWebhookSignature(rawBody, headers, webhookSecret)) {
            throw new WebhookSignatureInvalidException(connectionId);
        }

        if (!provider.isRelevantWebhookEvent(headers, new String(rawBody))) {
            log.debug("Ignoring non-actionable webhook event for connection={}", connectionId);
            return;
        }

        syncCommandProducer.publish(connection);
    }

    private String extractWebhookSecret(String configJson) {
        try {
            Map<String, Object> config = objectMapper.readValue(configJson, new TypeReference<>() {});
            Object secret = config.get("webhookSecret");
            return secret instanceof String s ? credentialEncryptor.decrypt(s) : null;
        } catch (Exception _) {
            return null;
        }
    }
}
