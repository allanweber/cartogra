package io.cartogra.registry.application.usecase.impl;

import io.cartogra.registry.application.dto.UpdateScmConnectionCommand;
import io.cartogra.registry.application.repository.ScmConnectionRepository;
import io.cartogra.registry.application.usecase.UpdateScmConnectionUseCase;
import io.cartogra.registry.domain.ScmConnection;
import io.cartogra.registry.domain.exception.ScmConnectionNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class UpdateScmConnectionUseCaseImpl implements UpdateScmConnectionUseCase {

    private final ScmConnectionRepository scmConnectionRepository;

    public UpdateScmConnectionUseCaseImpl(ScmConnectionRepository scmConnectionRepository) {
        this.scmConnectionRepository = scmConnectionRepository;
    }

    @Override
    @Transactional
    public ScmConnection execute(UpdateScmConnectionCommand command) {
        ScmConnection existing = scmConnectionRepository.findById(command.tenantId(), command.connectionId())
                .orElseThrow(() -> new ScmConnectionNotFoundException(command.connectionId()));

        return scmConnectionRepository.save(new ScmConnection(
                existing.id(),
                existing.tenantId(),
                command.provider() != null ? command.provider() : existing.provider(),
                command.config() != null ? command.config() : existing.config(),
                existing.createdAt(),
                Instant.now(),
                null
        ));
    }
}
