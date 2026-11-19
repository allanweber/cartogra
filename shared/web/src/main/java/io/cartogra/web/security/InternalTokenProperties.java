package io.cartogra.web.security;

import io.cartogra.common.validation.StrongSecret;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.internal-token")
@Validated
public record InternalTokenProperties(
        @NotEmpty @StrongSecret(minBytes = 32) String key,
        @NotNull @DefaultValue("60s") Duration ttl) {
}
