package io.cartogra.gateway.config;

import io.cartogra.common.validation.StrongSecret;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.jwt")
@Validated
public record JwtConfig(
    @NotEmpty @StrongSecret(minBytes = 32) String secret,
    @Positive long accessTokenExpirySeconds,
    @Positive long refreshTokenExpirySeconds,
    boolean cookieSecure
) {}
