package io.cartogra.gateway.api;

import io.cartogra.gateway.api.dto.ApiResponse;
import io.cartogra.gateway.api.dto.TenantOidcConfigRequest;
import io.cartogra.gateway.domain.TenantOidcConfigService;
import io.cartogra.gateway.domain.TenantOidcConfig;
import io.cartogra.gateway.infrastructure.security.JwtAuthentication;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auth/admin/oidc-config")
@PreAuthorize("hasRole('ADMIN')")
public class TenantOidcAdminController {

    private final TenantOidcConfigService service;
    private final TraceContext traceContext;

    public TenantOidcAdminController(TenantOidcConfigService service, TraceContext traceContext) {
        this.service = service;
        this.traceContext = traceContext;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TenantOidcConfig>> create(
            @Valid @RequestBody TenantOidcConfigRequest request,
            @AuthenticationPrincipal JwtAuthentication principal) {
        String traceId = traceContext.currentTraceId();
        TenantOidcConfig saved = service.create(principal.getTenantId(), request);
        return ResponseEntity.status(201)
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(saved, traceId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TenantOidcConfig>> getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtAuthentication principal) {
        String traceId = traceContext.currentTraceId();
        return service.get(id, principal.getTenantId())
            .map(config -> ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .<ApiResponse<TenantOidcConfig>>body(new ApiResponse<>(config, traceId)))
            .orElseGet(() -> ResponseEntity.notFound()
                .header("X-Trace-Id", traceId).build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TenantOidcConfig>> update(
            @PathVariable UUID id,
            @Valid @RequestBody TenantOidcConfigRequest request,
            @AuthenticationPrincipal JwtAuthentication principal) {
        String traceId = traceContext.currentTraceId();
        return service.update(id, principal.getTenantId(), request)
            .map(saved -> ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .<ApiResponse<TenantOidcConfig>>body(new ApiResponse<>(saved, traceId)))
            .orElseGet(() -> ResponseEntity.notFound()
                .header("X-Trace-Id", traceId).build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtAuthentication principal) {
        service.delete(id, principal.getTenantId());
        return ResponseEntity.noContent().build();
    }
}
