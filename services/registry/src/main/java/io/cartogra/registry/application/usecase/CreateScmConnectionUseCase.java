package io.cartogra.registry.application.usecase;

import io.cartogra.registry.application.dto.CreateScmConnectionCommand;
import io.cartogra.registry.domain.ScmConnection;

public interface CreateScmConnectionUseCase {
    ScmConnection execute(CreateScmConnectionCommand command);
}
