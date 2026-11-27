package io.cartogra.contract.infrastructure.slack;

import io.cartogra.contract.config.NotificationWebhookProperties;
import io.cartogra.contract.domain.DeliveryAttempt;
import io.cartogra.contract.domain.NotificationRule;
import io.cartogra.contract.repository.DeliveryAttemptRepository;
import io.cartogra.web.client.TraceparentRequestInterceptor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sends one simple text payload to a Slack or Teams webhook, with the shared retry policy. */
@Component
public class WebhookNotificationSender implements WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotificationSender.class);

    private final DeliveryAttemptRepository logRepository;
    private final RestClient restClient;
    private final NotificationWebhookProperties webhookProperties;

    public WebhookNotificationSender(DeliveryAttemptRepository logRepository,
                                     TraceparentRequestInterceptor traceparentInterceptor,
                                     NotificationWebhookProperties webhookProperties) {
        this.logRepository = logRepository;
        this.webhookProperties = webhookProperties;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor(traceparentInterceptor)
                .build();
    }

    @Override
    public List<DeliveryAttempt> send(UUID tenantId, UUID ruleId, @Nullable UUID checkId, String channel,
                                      String webhookUrl, String text) {
        String hint = NotificationRule.maskWebhook(webhookUrl);
        List<DeliveryAttempt> rows = new ArrayList<>();
        for (int attempt = 1; attempt <= DeliveryAttempt.MAX_ATTEMPTS; attempt++) {
            int waitMs = waitFor(attempt);
            if (waitMs > 0 && !wait(waitMs)) {
                return rows;
            }
            DeliveryAttempt pending = new DeliveryAttempt(UUID.randomUUID(), tenantId, ruleId, checkId, attempt,
                    channel, hint, waitMs, null, null, DeliveryAttempt.PENDING, Instant.now(), null);
            logRepository.insertPending(pending);
            try {
                ensurePublicDestination(webhookUrl);
                int status = restClient.post()
                        .uri(webhookUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("text", text))
                        .exchange((request, response) -> response.getStatusCode().value());
                boolean sent = status >= 200 && status < 300;
                boolean retryable = status == 429 || status >= 500;
                String outcome = sent ? DeliveryAttempt.SENT : DeliveryAttempt.FAILED;
                String errorCode = sent ? null : "HTTP_" + status;
                Instant finishedAt = Instant.now();
                logRepository.finish(tenantId, pending.id(), status, errorCode, outcome, finishedAt);
                rows.add(pending.markFinished(status, errorCode, outcome, finishedAt));
                if (retryable) {
                    log.warn("Webhook delivery attempt {} failed with status {} for rule {} on {}",
                            attempt, status, ruleId, channel);
                } else {
                    return rows;
                }
            } catch (RestClientException _) {
                Instant finishedAt = Instant.now();
                logRepository.finish(tenantId, pending.id(), null, "NETWORK_ERROR", DeliveryAttempt.FAILED,
                        finishedAt);
                rows.add(pending.markFinished(null, "NETWORK_ERROR", DeliveryAttempt.FAILED, finishedAt));
                log.warn("Webhook delivery attempt {} failed with a network error for rule {} on {}",
                        attempt, ruleId, channel);
            }
        }
        return rows;
    }

    private void ensurePublicDestination(String webhookUrl) {
        URI uri;
        try {
            uri = new URI(webhookUrl);
        } catch (URISyntaxException _) {
            throw new RestClientException("Webhook URL is not valid");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new RestClientException("Webhook URL has no host");
        }
        if (host.equalsIgnoreCase("localhost") || host.equalsIgnoreCase("127.0.0.1")) {
            if (!webhookProperties.allowLocalWebhooks()) {
                throw new RestClientException("Local webhook destinations are disabled");
            }
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException _) {
            throw new RestClientException("Webhook host cannot be resolved");
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress() || address.isMulticastAddress() || isUniqueLocal(address)) {
                throw new RestClientException("Webhook host resolves to a blocked address");
            }
        }
    }

    private static boolean isUniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    static int waitFor(int attempt) {
        return switch (attempt) {
            case 1 -> DeliveryAttempt.FIRST_WAIT_MS;
            case 2 -> DeliveryAttempt.SECOND_WAIT_MS;
            default -> DeliveryAttempt.THIRD_WAIT_MS;
        };
    }

    private static boolean wait(int waitMs) {
        try {
            Thread.sleep(waitMs);
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
