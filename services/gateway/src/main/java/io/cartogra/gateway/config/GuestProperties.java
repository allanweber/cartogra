package io.cartogra.gateway.config;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Guest demo access (ADR-0019). Minting is off when {@code tenantId} is unset. */
@ConfigurationProperties(prefix = "app.guest")
@Validated
public record GuestProperties(
    @Nullable UUID tenantId,
    @DefaultValue("60m") Duration tokenTtl,
    @DefaultValue("5") @Positive int mintsPerMinute
) {

    public boolean enabled() {
        return tenantId != null;
    }
}
