package io.cartogra.registry.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceHealthStatusTest {

    @ParameterizedTest
    @CsvSource({
            "healthy,           HEALTHY",
            "HEALTHY,           HEALTHY",
            "degraded,          DEGRADED",
            "DEGRADED,          DEGRADED",
            "unhealthy,         UNHEALTHY",
            "UNHEALTHY,         UNHEALTHY",
            "probe_auth_failed, PROBE_AUTH_FAILED",
            "PROBE_AUTH_FAILED, PROBE_AUTH_FAILED",
            "unknown,           UNKNOWN",
            "anything_else,     UNKNOWN",
            ",                  UNKNOWN"
    })
    void fromStringMapsCorrectly(String input, ServiceHealthStatus expected) {
        if (input == null || input.isBlank()) {
            assertThat(ServiceHealthStatus.fromString("")).isEqualTo(ServiceHealthStatus.UNKNOWN);
        } else {
            assertThat(ServiceHealthStatus.fromString(input.strip())).isEqualTo(expected);
        }
    }

    @Test
    void toDbValueIsLowercase() {
        assertThat(ServiceHealthStatus.HEALTHY.toDbValue()).isEqualTo("healthy");
        assertThat(ServiceHealthStatus.DEGRADED.toDbValue()).isEqualTo("degraded");
        assertThat(ServiceHealthStatus.UNHEALTHY.toDbValue()).isEqualTo("unhealthy");
        assertThat(ServiceHealthStatus.PROBE_AUTH_FAILED.toDbValue()).isEqualTo("probe_auth_failed");
        assertThat(ServiceHealthStatus.UNKNOWN.toDbValue()).isEqualTo("unknown");
    }
}
