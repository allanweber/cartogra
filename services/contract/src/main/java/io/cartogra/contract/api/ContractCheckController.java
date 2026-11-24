package io.cartogra.contract.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.identity.Actor;
import io.cartogra.contract.api.dto.ContractCheckRequest;
import io.cartogra.contract.api.dto.ContractCheckResponse;
import io.cartogra.contract.domain.ContractCheckService;
import io.cartogra.web.api.TracedResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ContractCheckController {

    private final ContractCheckService checkService;

    public ContractCheckController(ContractCheckService checkService) {
        this.checkService = checkService;
    }

    @PostMapping({"/check", "/check/"})
    public ResponseEntity<ApiResponse<ContractCheckResponse>> check(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @Valid @RequestBody ContractCheckRequest request) {
        return TracedResponse.ok(ContractCheckResponse.from(
                checkService.check(tenantId, actor, request.service(), request.spec())));
    }
}
