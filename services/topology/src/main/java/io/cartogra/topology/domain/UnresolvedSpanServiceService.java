package io.cartogra.topology.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.topology.config.SpanLinkProperties;
import io.cartogra.topology.domain.exception.UnresolvedSpanServiceAlreadyResolvedException;
import io.cartogra.topology.domain.exception.UnresolvedSpanServiceNotFoundException;
import io.cartogra.topology.repository.UnresolvedSpanServiceRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Owns the unresolved-name lifecycle: recorded from spans (case-insensitive, capped per tenant because span data is
 * untrusted), cleared when a service with that name registers, and dismissed for good by a user.
 */
@Service
public class UnresolvedSpanServiceService {

    private static final int MAX_LIMIT = 200;

    private final UnresolvedSpanServiceRepository repository;
    private final SpanLinkProperties spanLinkProperties;

    public UnresolvedSpanServiceService(UnresolvedSpanServiceRepository repository,
                                        SpanLinkProperties spanLinkProperties) {
        this.repository = repository;
        this.spanLinkProperties = spanLinkProperties;
    }

    /** Records each distinct name once, in sorted order so concurrent batches lock rows in the same order. */
    public void record(UUID tenantId, Collection<String> names, Instant seenAt) {
        Map<String, String> distinct = new TreeMap<>();
        names.forEach(name -> distinct.putIfAbsent(name.toLowerCase(Locale.ROOT), name));
        distinct.values().forEach(name ->
                repository.record(tenantId, name, seenAt, spanLinkProperties.maxUnresolvedPerTenant()));
    }

    public void markRegistered(UUID tenantId, String name) {
        repository.markRegistered(tenantId, name, Instant.now());
    }

    public PageResult<UnresolvedSpanService> list(UUID tenantId, int limit, int offset) {
        int boundedLimit = Math.clamp(limit, 1, MAX_LIMIT);
        int boundedOffset = Math.max(offset, 0);
        return PageResult.of(repository.findActive(tenantId, boundedLimit, boundedOffset),
                repository.countActive(tenantId), boundedLimit, boundedOffset);
    }

    public UnresolvedSpanService dismiss(UUID tenantId, @Nullable UUID userId, UUID id) {
        UnresolvedSpanService existing = repository.findById(tenantId, id)
                .orElseThrow(() -> new UnresolvedSpanServiceNotFoundException(id));
        if (existing.isResolved() || !repository.dismiss(tenantId, id, userId, Instant.now())) {
            throw new UnresolvedSpanServiceAlreadyResolvedException(id);
        }
        return repository.findById(tenantId, id).orElseThrow(() -> new UnresolvedSpanServiceNotFoundException(id));
    }
}
