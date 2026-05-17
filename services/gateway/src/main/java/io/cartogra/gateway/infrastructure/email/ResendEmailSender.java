package io.cartogra.gateway.infrastructure.email;

import io.cartogra.gateway.config.ResendConfig;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@ConditionalOnProperty(name = "app.resend.test-mode", havingValue = "false", matchIfMissing = true)
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);
    private static final MediaType JSON = MediaType.get("application/json");
    private static final String RESEND_URL = "https://api.resend.com/emails";

    private final OkHttpClient client;
    private final ResendConfig config;

    public ResendEmailSender(OkHttpClient client, ResendConfig config) {
        this.client = client;
        this.config = config;
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

    private void send(String jsonBody, String toEmail) {
        Request request = new Request.Builder()
            .url(RESEND_URL)
            .addHeader("Authorization", "Bearer " + config.apiKey())
            .post(RequestBody.create(jsonBody, JSON))
            .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("Resend API error for {}: status={}", toEmail, response.code());
            }
        } catch (IOException e) {
            log.error("Failed to send email to {}", toEmail, e);
        }
    }
}
