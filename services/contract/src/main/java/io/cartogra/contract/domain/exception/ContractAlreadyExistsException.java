package io.cartogra.contract.domain.exception;

import java.util.UUID;

public class ContractAlreadyExistsException extends RuntimeException {

    private final UUID contractId;

    public ContractAlreadyExistsException(UUID serviceId, UUID contractId) {
        super("Service " + serviceId + " already has a contract: " + contractId);
        this.contractId = contractId;
    }

    public UUID contractId() {
        return contractId;
    }
}
