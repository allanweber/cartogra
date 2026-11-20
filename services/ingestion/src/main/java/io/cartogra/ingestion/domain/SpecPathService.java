package io.cartogra.ingestion.domain;

import io.cartogra.common.identity.Actor;
import io.cartogra.common.identity.Roles;
import io.cartogra.ingestion.api.dto.SpecPathsRequest;
import io.cartogra.ingestion.domain.exception.InvalidSpecPathsException;
import io.cartogra.ingestion.repository.SpecPathRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The OpenAPI probe paths of each tenant. Custom paths are added to {@link SpecDiscoverer#DEFAULT_PATHS}: the probe
 * order is the custom paths first, then the defaults.
 */
@Service
public class SpecPathService {

    static final int MAX_PATHS = 10;
    static final int MAX_PATH_LENGTH = 256;
    private static final Pattern GLOB_CHARACTERS = Pattern.compile("[*?\\[\\]{}]");

    private final SpecPathRepository repository;
    private final IngestionAuditRecorder auditRecorder;

    public SpecPathService(SpecPathRepository repository, IngestionAuditRecorder auditRecorder) {
        this.repository = repository;
        this.auditRecorder = auditRecorder;
    }

    public SpecPaths get(UUID tenantId) {
        return new SpecPaths(SpecDiscoverer.DEFAULT_PATHS, repository.findCustomPaths(tenantId));
    }

    @PreAuthorize(Roles.ADMIN_ONLY)
    @Transactional
    public SpecPaths update(UUID tenantId, Actor actor, SpecPathsRequest request) {
        List<String> custom = validate(request.custom());
        SpecPaths before = get(tenantId);
        repository.saveCustomPaths(tenantId, custom);
        SpecPaths after = new SpecPaths(SpecDiscoverer.DEFAULT_PATHS, custom);
        auditRecorder.specPathsUpdated(actor, tenantId, before, after);
        return after;
    }

    /** The paths that a sync probes, in order: the custom paths first, then the defaults. */
    public List<String> probeOrder(UUID tenantId) {
        Set<String> order = new LinkedHashSet<>(repository.findCustomPaths(tenantId));
        order.addAll(SpecDiscoverer.DEFAULT_PATHS);
        return List.copyOf(order);
    }

    private static List<String> validate(List<String> requested) {
        if (requested == null) {
            throw new InvalidSpecPathsException("Validation failed", Map.of("custom", "must not be null"));
        }
        if (requested.size() > MAX_PATHS) {
            throw new InvalidSpecPathsException("Validation failed",
                    Map.of("custom", "must have " + MAX_PATHS + " entries or fewer"));
        }
        Map<String, Object> errors = new LinkedHashMap<>();
        List<String> paths = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : requested) {
            String path = raw == null ? "" : raw.strip();
            Optional<String> reason = reason(path)
                    .or(() -> seen.add(path) ? Optional.empty() : Optional.of("is a duplicate"));
            reason.ifPresentOrElse(r -> errors.putIfAbsent(path, r), () -> paths.add(path));
        }
        if (!errors.isEmpty()) {
            throw new InvalidSpecPathsException("Validation failed", errors);
        }
        return List.copyOf(paths);
    }

    private static Optional<String> reason(String path) {
        if (path.isEmpty()) {
            return Optional.of("must not be blank");
        }
        if (path.length() > MAX_PATH_LENGTH) {
            return Optional.of("must have " + MAX_PATH_LENGTH + " characters or fewer");
        }
        if (path.startsWith("/")) {
            return Optional.of("must be a relative path");
        }
        if (path.contains("..")) {
            return Optional.of("must not contain '..'");
        }
        if (GLOB_CHARACTERS.matcher(path).find()) {
            return Optional.of("must not contain glob characters");
        }
        if (SpecDiscoverer.DEFAULT_PATHS.contains(path)) {
            return Optional.of("is already a default path");
        }
        return Optional.empty();
    }
}
