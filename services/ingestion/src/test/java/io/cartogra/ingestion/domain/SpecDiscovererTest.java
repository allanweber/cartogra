package io.cartogra.ingestion.domain;

import io.cartogra.ingestion.infrastructure.scm.ScmProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpecDiscovererTest {

    private static final ScmRepository REPO =
            new ScmRepository("1", "payments", "acme/payments", "main", false, null, null);
    private static final ScmConnectionConfig CONFIG =
            new ScmConnectionConfig(UUID.randomUUID(), UUID.randomUUID(), "github", Map.of());
    private static final List<String> ORDER = List.of("custom/openapi.yaml", "openapi.yaml", "openapi.json");

    @Mock
    ScmProvider provider;

    SimpleMeterRegistry meters;
    SpecDiscoverer discoverer;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        discoverer = new SpecDiscoverer(meters);
        lenient().when(provider.getFileContents(any(), anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void givesTheFirstHitInProbeOrderAndStopsProbing() {
        when(provider.getFileContents(CONFIG, "acme/payments", "openapi.yaml")).thenReturn(Optional.of("openapi: 3.0.0"));

        Optional<SpecFile> spec = discoverer.discover(provider, CONFIG, REPO, ORDER);

        assertThat(spec).contains(new SpecFile("openapi.yaml", "openapi: 3.0.0"));
        verify(provider, never()).getFileContents(CONFIG, "acme/payments", "openapi.json");
    }

    @Test
    void aCustomPathWinsOverADefault() {
        when(provider.getFileContents(CONFIG, "acme/payments", "custom/openapi.yaml")).thenReturn(Optional.of("custom"));
        lenient().when(provider.getFileContents(CONFIG, "acme/payments", "openapi.yaml")).thenReturn(Optional.of("default"));

        assertThat(discoverer.discover(provider, CONFIG, REPO, ORDER)).map(SpecFile::filePath)
                .contains("custom/openapi.yaml");
    }

    @Test
    void noFileGivesEmpty() {
        assertThat(discoverer.discover(provider, CONFIG, REPO, ORDER)).isEmpty();
        assertThat(meters.find(SpecDiscoverer.SKIPPED_COUNTER).counters()).isEmpty();
    }

    @Test
    void aBlankFileIsSkippedAndCounted() {
        when(provider.getFileContents(CONFIG, "acme/payments", "openapi.yaml")).thenReturn(Optional.of("  \n"));

        assertThat(discoverer.discover(provider, CONFIG, REPO, ORDER)).isEmpty();
        assertThat(meters.counter(SpecDiscoverer.SKIPPED_COUNTER, "reason", "empty").count()).isEqualTo(1.0);
    }

    @Test
    void aFileOverTheSizeCapIsSkippedAndCounted() {
        String big = "a".repeat(SpecDiscoverer.MAX_SPEC_BYTES + 1);
        when(provider.getFileContents(CONFIG, "acme/payments", "openapi.yaml")).thenReturn(Optional.of(big));

        assertThat(discoverer.discover(provider, CONFIG, REPO, ORDER)).isEmpty();
        assertThat(meters.counter(SpecDiscoverer.SKIPPED_COUNTER, "reason", "too_large").count()).isEqualTo(1.0);
    }

    @Test
    void aFileAtTheSizeCapIsAccepted() {
        String max = "a".repeat(SpecDiscoverer.MAX_SPEC_BYTES);
        when(provider.getFileContents(CONFIG, "acme/payments", "openapi.yaml")).thenReturn(Optional.of(max));

        assertThat(discoverer.discover(provider, CONFIG, REPO, ORDER)).isPresent();
    }

    @Test
    void aFetchErrorIsTheSameAsNoFile() {
        when(provider.getFileContents(CONFIG, "acme/payments", "custom/openapi.yaml"))
                .thenThrow(new RuntimeException("boom"));
        when(provider.getFileContents(eq(CONFIG), eq("acme/payments"), eq("openapi.yaml")))
                .thenReturn(Optional.of("openapi: 3.0.0"));

        assertThat(discoverer.discover(provider, CONFIG, REPO, ORDER)).map(SpecFile::filePath).contains("openapi.yaml");
        assertThat(meters.find(SpecDiscoverer.SKIPPED_COUNTER).counters()).isEmpty();
    }
}
