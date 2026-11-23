package io.cartogra.topology.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "topology.outbox")
@Validated
public record OutboxProperties(
        @NotNull @DefaultValue("PT1S") Duration relayInterval,
        @Positive @DefaultValue("100") int batchSize,
        @NotNull @DefaultValue("PT10S") Duration sendTimeout) {
}
