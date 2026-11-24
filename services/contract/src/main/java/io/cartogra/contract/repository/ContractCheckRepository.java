package io.cartogra.contract.repository;

import io.cartogra.contract.domain.ContractCheck;

import java.util.UUID;

public interface ContractCheckRepository {

    void insert(ContractCheck check);

    long countByService(UUID tenantId, UUID serviceId);
}
