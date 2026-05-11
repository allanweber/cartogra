package io.cartogra.registry.application.usecase.impl;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.application.repository.ScmConnectionRepository;
import io.cartogra.registry.application.usecase.ListScmConnectionsUseCase;
import io.cartogra.registry.domain.ScmConnection;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class ListScmConnectionsUseCaseImpl implements ListScmConnectionsUseCase {

    private final ScmConnectionRepository scmConnectionRepository;

    public ListScmConnectionsUseCaseImpl(ScmConnectionRepository scmConnectionRepository) {
        this.scmConnectionRepository = scmConnectionRepository;
    }

    @Override
    public PageResult<ScmConnection> execute(UUID tenantId, int limit, int offset) {
        List<ScmConnection> items = scmConnectionRepository.findAll(tenantId, limit, offset);
        long total = scmConnectionRepository.count(tenantId);
        return PageResult.of(items, total, limit, offset);
    }
}
