package io.cartogra.ingestion.domain;

import io.cartogra.ingestion.infrastructure.scm.ScmProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Finds the OpenAPI file of a repository: it probes the paths in order and gives the first file that is not blank
 * and is not larger than {@link #MAX_SPEC_BYTES}. A fetch error counts as no file at that path.
 */
@Component
public class SpecDiscoverer {

    public static final List<String> DEFAULT_PATHS = List.of(
            "openapi.yaml", "openapi.yml", "openapi.json", "api/openapi.yaml", "docs/openapi.yaml");

    static final int MAX_SPEC_BYTES = 512 * 1024;
    static final String SKIPPED_COUNTER = "cartogra.ingestion.spec.skipped";

    private static final Logger log = LoggerFactory.getLogger(SpecDiscoverer.class);

    private final MeterRegistry meterRegistry;

    public SpecDiscoverer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Optional<SpecFile> discover(ScmProvider provider, ScmConnectionConfig config, ScmRepository repo,
                                       List<String> probeOrder) {
        for (String path : probeOrder) {
            Optional<String> content = safeGetFile(provider, config, repo, path);
            if (content.isEmpty()) {
                continue;
            }
            String spec = content.get();
            if (spec.isBlank()) {
                skip(repo, path, "empty");
                continue;
            }
            if (spec.getBytes(StandardCharsets.UTF_8).length > MAX_SPEC_BYTES) {
                skip(repo, path, "too_large");
                continue;
            }
            return Optional.of(new SpecFile(path, spec));
        }
        return Optional.empty();
    }

    private void skip(ScmRepository repo, String path, String reason) {
        log.warn("Skipped the spec {} of repo={}: reason={}", path, repo.fullPath(), reason);
        meterRegistry.counter(SKIPPED_COUNTER, "reason", reason).increment();
    }

    private Optional<String> safeGetFile(ScmProvider provider, ScmConnectionConfig config,
                                         ScmRepository repo, String filePath) {
        try {
            return provider.getFileContents(config, repo.fullPath(), filePath);
        } catch (Exception e) {
            log.debug("Could not fetch {} from {}: {}", filePath, repo.fullPath(), e.getMessage());
            return Optional.empty();
        }
    }
}
