package io.cartogra.ingestion.api;

import io.cartogra.ingestion.domain.WebhookService;
import io.cartogra.ingestion.domain.exception.WebhookConnectionNotFoundException;
import io.cartogra.ingestion.domain.exception.WebhookSignatureInvalidException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WebhookControllerTest {

    @Mock
    WebhookService webhookService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WebhookController(webhookService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void receiveReturns202AndDelegatesBody() throws Exception {
        UUID connectionId = UUID.randomUUID();
        byte[] payload = """
                {"action":"push"}""".getBytes();

        mockMvc.perform(post("/webhooks/github/{connectionId}", connectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-GitHub-Event", "push")
                        .header("X-Hub-Signature-256", "sha256=abc123"))
                .andExpect(status().isAccepted());

        verify(webhookService).process(eq("github"), eq(connectionId), any(byte[].class), any());
    }

    @Test
    void receiveWithEmptyBodyReturns202() throws Exception {
        UUID connectionId = UUID.randomUUID();

        mockMvc.perform(post("/webhooks/github/{connectionId}", connectionId))
                .andExpect(status().isAccepted());
    }

    @Test
    void receiveConnectionNotFoundReturns404() throws Exception {
        UUID connectionId = UUID.randomUUID();
        doThrow(new WebhookConnectionNotFoundException("github/" + connectionId))
                .when(webhookService).process(any(), any(), any(), any());

        mockMvc.perform(post("/webhooks/github/{connectionId}", connectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WEBHOOK_CONNECTION_NOT_FOUND"));
    }

    @Test
    void receiveInvalidSignatureReturns401() throws Exception {
        UUID connectionId = UUID.randomUUID();
        doThrow(new WebhookSignatureInvalidException(connectionId))
                .when(webhookService).process(any(), any(), any(), any());

        mockMvc.perform(post("/webhooks/github/{connectionId}", connectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("WEBHOOK_SIGNATURE_INVALID"));
    }

    @Test
    void receivePassesProviderTypeAndConnectionIdFromPath() throws Exception {
        UUID connectionId = UUID.randomUUID();

        mockMvc.perform(post("/webhooks/azuredevops/{connectionId}", connectionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted());

        verify(webhookService).process(eq("azuredevops"), eq(connectionId), any(), any());
    }
}
