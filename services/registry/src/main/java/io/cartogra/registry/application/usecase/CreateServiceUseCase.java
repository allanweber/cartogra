package io.cartogra.registry.application.usecase;

import io.cartogra.registry.application.dto.CreateServiceCommand;
import io.cartogra.registry.domain.Service;

public interface CreateServiceUseCase {
    Service execute(CreateServiceCommand command);
}
