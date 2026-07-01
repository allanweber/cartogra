package io.cartogra.gateway.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.jwt")
@Validated
public record JwtConfig(
    @NotEmpty String secret,
    @Positive long accessTokenExpirySeconds,
    @Positive long refreshTokenExpirySeconds,
    boolean cookieSecure
) {}
