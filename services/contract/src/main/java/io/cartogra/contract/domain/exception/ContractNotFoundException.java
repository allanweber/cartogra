package io.cartogra.contract.domain.exception;

import java.util.UUID;

public class ContractNotFoundException extends RuntimeException {
    public ContractNotFoundException(UUID id) {
        super("Contract not found: " + id);
    }
}
