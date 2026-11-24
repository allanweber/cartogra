package io.cartogra.contract.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code service} is a service id or a service name. */
public record ContractCheckRequest(
        @NotBlank @Size(max = 255) String service,
        @NotBlank @Size(max = ContractRequest.MAX_SPEC_CHARS) String spec
) {}
