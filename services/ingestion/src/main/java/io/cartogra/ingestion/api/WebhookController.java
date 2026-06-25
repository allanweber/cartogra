package io.cartogra.ingestion.api;

import io.cartogra.ingestion.domain.WebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Webhook receiver. Per the platform contract, webhook endpoints are NOT wrapped in the
 * standard response envelope. Signature verification and event filtering live in the service.
 */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/{providerType}/{connectionId}")
    public ResponseEntity<Void> receive(
            @PathVariable String providerType,
            @PathVariable UUID connectionId,
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader Map<String, String> headers) {
        Map<String, String> caseInsensitiveHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        caseInsensitiveHeaders.putAll(headers);
        webhookService.process(
                providerType,
                connectionId,
                rawBody != null ? rawBody : new byte[0],
                caseInsensitiveHeaders);
        return ResponseEntity.accepted().build();
    }
}
