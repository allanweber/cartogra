package io.cartogra.registry.application.usecase;

import io.cartogra.registry.application.dto.UpdateServiceCommand;
import io.cartogra.registry.domain.Service;

public interface UpdateServiceUseCase {
    Service execute(UpdateServiceCommand command);
}
