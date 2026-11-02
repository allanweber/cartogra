package io.cartogra.topology.api;

import io.cartogra.common.identity.Actor;
import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.topology.api.dto.DriftResponse;
import io.cartogra.topology.api.dto.ResolveDriftRequest;
import io.cartogra.topology.domain.DriftService;
import io.cartogra.web.api.TracedResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/drifts")
public class DriftController {

    private final DriftService driftService;

    public DriftController(DriftService driftService) {
        this.driftService = driftService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<DriftResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return TracedResponse.ok(driftService.list(tenantId, limit, offset).map(DriftResponse::from));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<ApiResponse<DriftResponse>> resolve(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @PathVariable UUID id,
            @Valid @RequestBody ResolveDriftRequest request) {
        return TracedResponse.ok(DriftResponse.from(driftService.resolve(tenantId, actor, id, request.action())));
    }
}
