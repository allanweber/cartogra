package io.cartogra.contract.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.common.identity.Actor;
import io.cartogra.contract.api.dto.ConsumerResponse;
import io.cartogra.contract.api.dto.ContractRequest;
import io.cartogra.contract.api.dto.ContractResponse;
import io.cartogra.contract.api.dto.ContractSummaryResponse;
import io.cartogra.contract.api.dto.VersionDiffResponse;
import io.cartogra.contract.api.dto.ContractVersionResponse;
import io.cartogra.contract.domain.ConsumerService;
import io.cartogra.contract.domain.ContractService;
import io.cartogra.contract.domain.VersionPublication;
import io.cartogra.contract.domain.exception.InvalidParameterException;
import io.cartogra.web.api.TracedResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ContractController {

    private final ContractService contractService;
    private final ConsumerService consumerService;

    public ContractController(ContractService contractService, ConsumerService consumerService) {
        this.contractService = contractService;
        this.consumerService = consumerService;
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

    @GetMapping({"", "/"})
    public ResponseEntity<ApiResponse<PageResult<ContractSummaryResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return TracedResponse.ok(contractService.list(tenantId, q, breakingFilter(status), limit, offset)
                .map(ContractSummaryResponse::from));
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<ApiResponse<PageResult<ContractVersionResponse>>> listVersions(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return TracedResponse.ok(contractService.listVersions(tenantId, id, limit, offset)
                .map(ContractVersionResponse::from));
    }

    private static @Nullable Boolean breakingFilter(@Nullable String status) {
        if (status == null || status.isEmpty()) {
            return null;
        }
        return switch (status) {
            case "breaking" -> true;
            case "compatible" -> false;
            default -> throw new InvalidParameterException("status", "must be breaking or compatible");
        };
    }

    @GetMapping("/{id}/versions/{versionNumber}/diff")
    public ResponseEntity<ApiResponse<VersionDiffResponse>> diff(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id,
            @PathVariable int versionNumber) {
        return TracedResponse.ok(VersionDiffResponse.from(contractService.diff(tenantId, id, versionNumber)));
    }

    @GetMapping("/{id}/consumers")
    public ResponseEntity<ApiResponse<PageResult<ConsumerResponse>>> consumers(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return TracedResponse.ok(consumerService.consumersOf(tenantId, id, limit, offset).map(ConsumerResponse::from));
    }
}
