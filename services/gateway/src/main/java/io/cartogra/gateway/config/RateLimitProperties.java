package io.cartogra.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
    boolean enabled,
    int defaultReplenishRate,
    int defaultBurstCapacity,
    int authReplenishRate,
    int authBurstCapacity
) {}
