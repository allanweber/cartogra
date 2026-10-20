package io.cartogra.topology.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "topology.drift")
@Validated
public record DriftProperties(
        @NotNull @DefaultValue("PT5M") Duration sweepInterval,
        @NotNull @DefaultValue("P7D") Duration missingAfter) {
}
