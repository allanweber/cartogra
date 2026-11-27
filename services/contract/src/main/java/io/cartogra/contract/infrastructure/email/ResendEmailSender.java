package io.cartogra.contract.infrastructure.email;

import io.cartogra.contract.config.NotificationEmailProperties;
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

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sends email through Resend from Contract, with the same retry policy as the webhook channels. */
@Component
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

    private final DeliveryAttemptRepository logRepository;
    private final NotificationEmailProperties properties;
    private final RestClient restClient;

    public ResendEmailSender(DeliveryAttemptRepository logRepository, NotificationEmailProperties properties,
                             TraceparentRequestInterceptor traceparentInterceptor) {
        this.logRepository = logRepository;
        this.properties = properties;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .requestInterceptor(traceparentInterceptor)
                .build();
    }

    @Override
    public List<DeliveryAttempt> send(UUID tenantId, UUID ruleId, @Nullable UUID checkId, String emailAddress,
                                      String text) {
        String hint = NotificationRule.maskEmail(emailAddress);
        Map<String, Object> body = Map.of(
                "from", properties.fromAddress(),
                "to", List.of(emailAddress),
                "subject", "Breaking contract change",
                "text", text);
        List<DeliveryAttempt> rows = new ArrayList<>();
        for (int attempt = 1; attempt <= DeliveryAttempt.MAX_ATTEMPTS; attempt++) {
            int waitMs = waitFor(attempt);
            if (waitMs > 0 && !wait(waitMs)) {
                return rows;
            }
            DeliveryAttempt pending = new DeliveryAttempt(UUID.randomUUID(), tenantId, ruleId, checkId, attempt,
                    DeliveryAttempt.EMAIL, hint, waitMs, null, null, DeliveryAttempt.PENDING, Instant.now(), null);
            logRepository.insertPending(pending);
            try {
                int status = restClient.post()
                        .uri("/emails")
                        .header("Authorization", "Bearer " + properties.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .exchange((request, response) -> response.getStatusCode().value());
                boolean sent = status >= 200 && status < 300;
                boolean retryable = status == 429 || status >= 500;
                String outcome = sent ? DeliveryAttempt.SENT : DeliveryAttempt.FAILED;
                String errorCode = sent ? null : "HTTP_" + status;
                Instant finishedAt = Instant.now();
                logRepository.finish(tenantId, pending.id(), status, errorCode, outcome, finishedAt);
                rows.add(pending.markFinished(status, errorCode, outcome, finishedAt));
                if (retryable) {
                    log.warn("Email delivery attempt {} failed with status {} for rule {}", attempt, status, ruleId);
                } else {
                    return rows;
                }
            } catch (RestClientException _) {
                Instant finishedAt = Instant.now();
                logRepository.finish(tenantId, pending.id(), null, "NETWORK_ERROR", DeliveryAttempt.FAILED,
                        finishedAt);
                rows.add(pending.markFinished(null, "NETWORK_ERROR", DeliveryAttempt.FAILED, finishedAt));
                log.warn("Email delivery attempt {} failed with a network error for rule {}", attempt, ruleId);
            }
        }
        return rows;
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
