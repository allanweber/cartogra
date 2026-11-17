package io.cartogra.contract.domain.exception;

import java.util.UUID;

public class VersionNotFoundException extends RuntimeException {
    public VersionNotFoundException(UUID contractId, int versionNumber) {
        super("Contract " + contractId + " has no version " + versionNumber);
    }
}
