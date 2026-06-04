package io.cartogra.registry.application.usecase.impl;

import io.cartogra.registry.application.dto.CreateScmConnectionCommand;
import io.cartogra.registry.application.repository.ScmConnectionRepository;
import io.cartogra.registry.application.usecase.CreateScmConnectionUseCase;
import io.cartogra.registry.domain.ScmConnection;
import io.cartogra.registry.infrastructure.kafka.SyncCommandProducer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Component
public class CreateScmConnectionUseCaseImpl implements CreateScmConnectionUseCase {

    private final ScmConnectionRepository scmConnectionRepository;
    private final SyncCommandProducer syncCommandProducer;

    public CreateScmConnectionUseCaseImpl(ScmConnectionRepository scmConnectionRepository,
                                          SyncCommandProducer syncCommandProducer) {
        this.scmConnectionRepository = scmConnectionRepository;
        this.syncCommandProducer = syncCommandProducer;
    }

    @Override
    @Transactional
    public ScmConnection execute(CreateScmConnectionCommand command) {
        Instant now = Instant.now();
        ScmConnection connection = scmConnectionRepository.save(new ScmConnection(
                UUID.randomUUID(),
                command.tenantId(),
                command.provider(),
                command.config() != null ? command.config() : "{}",
                now, now, null
        ));
        syncCommandProducer.publish(connection);
        return connection;
    }
}
