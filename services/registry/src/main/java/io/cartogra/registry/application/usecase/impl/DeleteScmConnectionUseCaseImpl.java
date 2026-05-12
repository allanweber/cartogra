package io.cartogra.registry.application.usecase.impl;

import io.cartogra.registry.application.repository.ScmConnectionRepository;
import io.cartogra.registry.application.usecase.DeleteScmConnectionUseCase;
import io.cartogra.registry.domain.exception.ScmConnectionNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class DeleteScmConnectionUseCaseImpl implements DeleteScmConnectionUseCase {

    private final ScmConnectionRepository scmConnectionRepository;

    public DeleteScmConnectionUseCaseImpl(ScmConnectionRepository scmConnectionRepository) {
        this.scmConnectionRepository = scmConnectionRepository;
    }

    @Override
    @Transactional
    public void execute(UUID tenantId, UUID connectionId) {
        scmConnectionRepository.findById(tenantId, connectionId)
                .orElseThrow(() -> new ScmConnectionNotFoundException(connectionId));
        scmConnectionRepository.softDelete(tenantId, connectionId);
    }
}
