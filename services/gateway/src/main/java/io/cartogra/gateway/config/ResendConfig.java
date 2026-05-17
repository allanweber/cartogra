package io.cartogra.gateway.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.resend")
@Validated
public record ResendConfig(
    @NotEmpty String apiKey,
    @NotEmpty String fromAddress,
    boolean testMode
) {}
