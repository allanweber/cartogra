package io.cartogra.contract.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Resend values read by Contract only; the key may be blank until an environment supplies it. */
@Validated
@ConfigurationProperties(prefix = "contract.resend")
public record NotificationEmailProperties(
        @NotNull String apiKey,
        @NotBlank String fromAddress,
        @NotBlank String baseUrl
) {
}
