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
        String body = """
            {
              "from": "%s",
              "to": ["%s"],
              "subject": "Verify your Cartogra account",
              "html": "<p>Your verification code is: <strong>%s</strong></p><p>This code expires in 15 minutes.</p>"
            }
            """.formatted(config.fromAddress(), toEmail, otpToken);
        send(body, toEmail);
    }

    @Override
    public void sendPasswordReset(String toEmail, String resetToken) {
        String body = """
            {
              "from": "%s",
              "to": ["%s"],
              "subject": "Reset your Cartogra password",
              "html": "<p>Your password reset token is: <strong>%s</strong></p><p>This token expires in 15 minutes.</p>"
            }
            """.formatted(config.fromAddress(), toEmail, resetToken);
        send(body, toEmail);
    }

    @Override
    public void sendInvite(String toEmail, String tenantName, String inviteToken) {
        String acceptUrl = frontendProperties.baseUrl() + "/accept-invite?token=" + inviteToken;
        String body = """
            {
              "from": "%s",
              "to": ["%s"],
              "subject": "You're invited to join Cartogra",
              "html": "<p>You've been invited to join a Cartogra - %s. <a href=\\"%s\\">Accept your invite</a></p><p>This link expires in 7 days.</p>"
            }
            """.formatted(config.fromAddress(), toEmail, tenantName, acceptUrl);
        send(body, toEmail);
    }

    private void send(String jsonBody, String toEmail) {
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
