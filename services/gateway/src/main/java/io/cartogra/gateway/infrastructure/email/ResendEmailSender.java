package io.cartogra.gateway.infrastructure.email;

import io.cartogra.gateway.config.FrontendProperties;
import io.cartogra.gateway.config.ResendConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.web.util.HtmlUtils.htmlEscape;

@Component
@ConditionalOnProperty(name = "app.resend.test-mode", havingValue = "false", matchIfMissing = true)
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);
    private static final String RESEND_URL = "https://api.resend.com/emails";

    private final RestClient restClient;
    private final ResendConfig config;
    private final FrontendProperties frontendProperties;

    public ResendEmailSender(RestClient.Builder builder, ResendConfig config, FrontendProperties frontendProperties) {
        this.restClient = builder.build();
        this.config = config;
        this.frontendProperties = frontendProperties;
    }

    @Override
    public void sendVerification(String toEmail, String otpToken) {
        send(toEmail, "Verify your Cartogra account",
            "<p>Your verification code is: <strong>" + htmlEscape(otpToken) + "</strong></p>"
                + "<p>This code expires in 15 minutes.</p>");
    }

    @Override
    public void sendPasswordReset(String toEmail, String resetToken) {
        send(toEmail, "Reset your Cartogra password",
            "<p>Your password reset token is: <strong>" + htmlEscape(resetToken) + "</strong></p>"
                + "<p>This token expires in 15 minutes.</p>");
    }

    @Override
    public void sendInvite(String toEmail, String tenantName, String inviteToken) {
        String acceptUrl = frontendProperties.baseUrl() + "/accept-invite?token="
            + URLEncoder.encode(inviteToken, StandardCharsets.UTF_8);
        send(toEmail, "You're invited to join Cartogra",
            "<p>You've been invited to join a Cartogra - " + htmlEscape(tenantName) + ". "
                + "<a href=\"" + htmlEscape(acceptUrl) + "\">Accept your invite</a></p>"
                + "<p>This link expires in 7 days.</p>");
    }

    /** The JSON body is serialized by the HTTP client, so no value can break out of its field. */
    private void send(String toEmail, String subject, String html) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", config.fromAddress());
        body.put("to", List.of(toEmail));
        body.put("subject", subject);
        body.put("html", html);
        send(body, toEmail);
    }

    private void send(Map<String, Object> jsonBody, String toEmail) {
        try {
            restClient.post()
                .uri(RESEND_URL)
                .header("Authorization", "Bearer " + config.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) ->
                    log.error("Resend API error for {}: status={}", toEmail, res.getStatusCode()))
                .toBodilessEntity();
        } catch (Exception e) {
            log.error("Failed to send email to {}", toEmail, e);
        }
    }
}
