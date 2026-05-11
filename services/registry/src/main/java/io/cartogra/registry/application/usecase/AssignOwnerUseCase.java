package io.cartogra.registry.application.usecase;

import io.cartogra.registry.application.dto.AssignOwnerCommand;
import io.cartogra.registry.domain.Service;

public interface AssignOwnerUseCase {
    Service execute(AssignOwnerCommand command);
}
