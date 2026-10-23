package io.cartogra.topology.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.topology.domain.exception.UnresolvedSpanServiceAlreadyResolvedException;
import io.cartogra.topology.domain.exception.UnresolvedSpanServiceNotFoundException;
import io.cartogra.topology.repository.UnresolvedSpanServiceRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class UnresolvedSpanServiceService {

    private static final int MAX_LIMIT = 200;

    private final UnresolvedSpanServiceRepository repository;

    public UnresolvedSpanServiceService(UnresolvedSpanServiceRepository repository) {
        this.repository = repository;
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
