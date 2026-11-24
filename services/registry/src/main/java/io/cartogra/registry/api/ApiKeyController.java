package io.cartogra.registry.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.common.identity.Actor;
import io.cartogra.registry.api.dto.ApiKeyRequest;
import io.cartogra.registry.api.dto.ApiKeyResponse;
import io.cartogra.registry.api.dto.CreatedApiKeyResponse;
import io.cartogra.registry.domain.ApiKeyService;
import io.cartogra.web.api.TracedResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api-keys")
public class ApiKeyController {

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreatedApiKeyResponse>> create(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @Valid @RequestBody ApiKeyRequest req) {
        var created = service.create(tenantId, actor, req.name(), req.scopes(), req.expiresAt());
        return TracedResponse.created(CreatedApiKeyResponse.from(created));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<ApiKeyResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return TracedResponse.ok(service.list(tenantId, limit, offset).map(ApiKeyResponse::from));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @PathVariable UUID id) {
        service.revoke(tenantId, actor, id);
        return TracedResponse.noContent();
    }
}
