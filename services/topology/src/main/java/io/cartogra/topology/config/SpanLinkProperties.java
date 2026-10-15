package io.cartogra.topology.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "topology.span-links")
@Validated
public record SpanLinkProperties(
        @NotNull @DefaultValue("PT5M") Duration ttl,
        @NotNull @DefaultValue("PT1M") Duration reapInterval,
        @Positive @DefaultValue("1000") int maxUnresolvedPerTenant) {
}
