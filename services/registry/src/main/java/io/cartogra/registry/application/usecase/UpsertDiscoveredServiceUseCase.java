package io.cartogra.registry.application.usecase;

import io.cartogra.registry.application.dto.ServiceDiscoveryCommand;
import io.cartogra.registry.domain.Service;

public interface UpsertDiscoveredServiceUseCase {

    Service execute(ServiceDiscoveryCommand command);
}
