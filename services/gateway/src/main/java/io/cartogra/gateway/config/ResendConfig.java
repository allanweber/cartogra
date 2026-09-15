package io.cartogra.gateway.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.resend")
@Validated
public record ResendConfig(
    String apiKey,
    @NotEmpty String fromAddress,
    boolean testMode
) {

    @AssertTrue(message = "apiKey must not be empty unless test-mode is true")
    public boolean isApiKeyPresentUnlessTestMode() {
        return testMode || (apiKey != null && !apiKey.isBlank());
    }
}
