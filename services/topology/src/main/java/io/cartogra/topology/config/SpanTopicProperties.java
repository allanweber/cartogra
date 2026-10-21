package io.cartogra.topology.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "topology.span-topics")
@Validated
public record SpanTopicProperties(
        @NotNull @DefaultValue("PT1H") Duration retention,
        @NotNull @DefaultValue("P3D") Duration dlqRetention) {
}
