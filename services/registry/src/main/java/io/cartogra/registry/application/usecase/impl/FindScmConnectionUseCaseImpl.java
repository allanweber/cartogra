package io.cartogra.registry.application.usecase.impl;

import io.cartogra.registry.application.repository.ScmConnectionRepository;
import io.cartogra.registry.application.usecase.FindScmConnectionUseCase;
import io.cartogra.registry.domain.ScmConnection;
import io.cartogra.registry.domain.exception.ScmConnectionNotFoundException;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class FindScmConnectionUseCaseImpl implements FindScmConnectionUseCase {

    private final ScmConnectionRepository scmConnectionRepository;

    public FindScmConnectionUseCaseImpl(ScmConnectionRepository scmConnectionRepository) {
        this.scmConnectionRepository = scmConnectionRepository;
    }

    @Override
    public ScmConnection execute(UUID tenantId, UUID connectionId) {
        return scmConnectionRepository.findById(tenantId, connectionId)
                .orElseThrow(() -> new ScmConnectionNotFoundException(connectionId));
    }
}
