package io.cartogra.contract.infrastructure.slack;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cartogra.contract.config.NotificationWebhookProperties;
import io.cartogra.contract.domain.DeliveryAttempt;
import io.cartogra.contract.repository.DeliveryAttemptRepository;
import io.cartogra.web.client.TraceparentRequestInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebhookNotificationSenderTest {

    private static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    @BeforeAll
    static void startWireMock() {
        WIRE_MOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @AfterEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
    }

    @Test
    void blockedLocalWebhookWritesOneFailureBeforeRetryWait() {
        UUID tenant = UUID.randomUUID();
        UUID rule = UUID.randomUUID();
        DeliveryAttemptRepository repository = mock(DeliveryAttemptRepository.class);
        WebhookNotificationSender sender = new WebhookNotificationSender(repository,
                new TraceparentRequestInterceptor(), new NotificationWebhookProperties(false));
        String url = "http://localhost:" + WIRE_MOCK.port() + "/hook";

        List<DeliveryAttempt> rows;
        Thread.currentThread().interrupt();
        try {
            rows = sender.send(tenant, rule, null, DeliveryAttempt.SLACK, url, "test text");
        } finally {
            Thread.interrupted();
        }

        assertThat(rows).hasSize(1);
        DeliveryAttempt row = rows.get(0);
        assertThat(row.errorCode()).isEqualTo("NETWORK_ERROR");
        assertThat(row.outcome()).isEqualTo(DeliveryAttempt.FAILED);
        assertThat(row.statusCode()).isNull();
        verify(repository).insertPending(any(DeliveryAttempt.class));
        verify(repository).finish(eq(tenant), eq(row.id()), isNull(), eq("NETWORK_ERROR"),
                eq(DeliveryAttempt.FAILED), any(Instant.class));
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo("/hook")))).isEmpty();
    }

    @Test
    void allowedLocalWebhookWritesOneSentRow() {
        UUID tenant = UUID.randomUUID();
        UUID rule = UUID.randomUUID();
        DeliveryAttemptRepository repository = mock(DeliveryAttemptRepository.class);
        WebhookNotificationSender sender = new WebhookNotificationSender(repository,
                new TraceparentRequestInterceptor(), new NotificationWebhookProperties(true));
        String url = "http://localhost:" + WIRE_MOCK.port() + "/hook";
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/hook")).willReturn(aResponse().withStatus(200)));

        List<DeliveryAttempt> rows = sender.send(tenant, rule, null, DeliveryAttempt.SLACK, url, "test text");

        assertThat(rows).hasSize(1);
        DeliveryAttempt row = rows.get(0);
        assertThat(row.statusCode()).isEqualTo(200);
        assertThat(row.outcome()).isEqualTo(DeliveryAttempt.SENT);
        assertThat(row.errorCode()).isNull();
        verify(repository).insertPending(any(DeliveryAttempt.class));
        verify(repository).finish(eq(tenant), eq(row.id()), eq(200), isNull(),
                eq(DeliveryAttempt.SENT), any(Instant.class));
        assertThat(WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo("/hook")))).hasSize(1);
    }
}
