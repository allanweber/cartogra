package io.cartogra.contract.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** {@code serviceId} is required on create and ignored when publishing a version. */
public record ContractRequest(
        @Nullable UUID serviceId,
        @NotBlank @Size(max = ContractRequest.MAX_SPEC_CHARS) String spec
) {
    public static final int MAX_SPEC_CHARS = 1_048_576;
}
