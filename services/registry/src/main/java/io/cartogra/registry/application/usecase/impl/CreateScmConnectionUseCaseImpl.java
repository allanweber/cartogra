package io.cartogra.registry.application.usecase.impl;

import io.cartogra.registry.application.dto.CreateScmConnectionCommand;
import io.cartogra.registry.application.repository.ScmConnectionRepository;
import io.cartogra.registry.application.usecase.CreateScmConnectionUseCase;
import io.cartogra.registry.domain.ScmConnection;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class CreateScmConnectionUseCaseImpl implements CreateScmConnectionUseCase {

    private final ScmConnectionRepository scmConnectionRepository;

    public CreateScmConnectionUseCaseImpl(ScmConnectionRepository scmConnectionRepository) {
        this.scmConnectionRepository = scmConnectionRepository;
    }

    @Override
    @Transactional
    public ScmConnection execute(CreateScmConnectionCommand command) {
        Instant now = Instant.now();
        return scmConnectionRepository.save(new ScmConnection(
                UUID.randomUUID(),
                command.tenantId(),
                command.provider(),
                command.config() != null ? command.config() : "{}",
                now, now, null
        ));
    }
}
