package io.cartogra.contract.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.identity.Actor;
import io.cartogra.contract.api.dto.ContractRequest;
import io.cartogra.contract.api.dto.ContractResponse;
import io.cartogra.contract.api.dto.ContractVersionResponse;
import io.cartogra.contract.domain.ContractService;
import io.cartogra.contract.domain.VersionPublication;
import io.cartogra.web.api.TracedResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @PostMapping({"", "/"})
    public ResponseEntity<ApiResponse<ContractResponse>> create(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @Valid @RequestBody ContractRequest request) {
        return TracedResponse.created(ContractResponse.from(contractService.create(tenantId, actor, request)));
    }

    @PostMapping("/{id}/versions")
    public ResponseEntity<ApiResponse<ContractVersionResponse>> publishVersion(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @PathVariable UUID id,
            @Valid @RequestBody ContractRequest request) {
        VersionPublication publication = contractService.publishVersion(tenantId, actor, id, request);
        ContractVersionResponse body = ContractVersionResponse.from(publication.version());
        return publication.created() ? TracedResponse.created(body) : TracedResponse.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ContractResponse>> get(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        return TracedResponse.ok(ContractResponse.from(contractService.get(tenantId, id)));
    }
}
