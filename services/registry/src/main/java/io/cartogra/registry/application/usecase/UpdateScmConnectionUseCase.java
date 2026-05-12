package io.cartogra.registry.application.usecase;

import io.cartogra.registry.application.dto.UpdateScmConnectionCommand;
import io.cartogra.registry.domain.ScmConnection;

public interface UpdateScmConnectionUseCase {
    ScmConnection execute(UpdateScmConnectionCommand command);
}
