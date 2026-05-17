package io.cartogra.gateway.api;

import io.cartogra.gateway.api.dto.ApiResponse;
import io.cartogra.gateway.api.dto.TenantOidcConfigRequest;
import io.cartogra.gateway.domain.TenantOidcConfig;
import io.cartogra.gateway.infrastructure.jdbc.TenantOidcConfigRepository;
import io.cartogra.gateway.infrastructure.security.JwtAuthentication;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/auth/admin/oidc-config")
@PreAuthorize("hasRole('ADMIN')")
public class TenantOidcAdminController {

    private final TenantOidcConfigRepository repository;
    private final TraceContext traceContext;

    public TenantOidcAdminController(TenantOidcConfigRepository repository, TraceContext traceContext) {
        this.repository = repository;
        this.traceContext = traceContext;
    }

    @PostMapping
    public Mono<ResponseEntity<ApiResponse<TenantOidcConfig>>> create(
            @Valid @RequestBody TenantOidcConfigRequest request,
            @AuthenticationPrincipal JwtAuthentication principal) {
        return Mono.deferContextual(ctx -> {
            String traceId = traceContext.currentTraceId(ctx);
            TenantOidcConfig config = new TenantOidcConfig(null, principal.getTenantId(),
                request.discoveryUri(), request.clientId(), request.clientSecret(),
                true, Instant.now(), Instant.now(), null);
            TenantOidcConfig saved = repository.save(config);
            return Mono.just(ResponseEntity.status(201)
                .header("X-Trace-Id", traceId)
                .<ApiResponse<TenantOidcConfig>>body(new ApiResponse<>(saved, traceId)));
        });
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<ApiResponse<TenantOidcConfig>>> getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtAuthentication principal) {
        return Mono.deferContextual(ctx -> {
            String traceId = traceContext.currentTraceId(ctx);
            return repository.findByIdAndTenantId(id, principal.getTenantId())
                .map(config -> ResponseEntity.ok()
                    .header("X-Trace-Id", traceId)
                    .<ApiResponse<TenantOidcConfig>>body(new ApiResponse<>(config, traceId)))
                .map(Mono::just)
                .orElseGet(() -> Mono.just(ResponseEntity.notFound()
                    .header("X-Trace-Id", traceId).build()));
        });
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<ApiResponse<TenantOidcConfig>>> update(
            @PathVariable UUID id,
            @Valid @RequestBody TenantOidcConfigRequest request,
            @AuthenticationPrincipal JwtAuthentication principal) {
        return Mono.deferContextual(ctx -> {
            String traceId = traceContext.currentTraceId(ctx);
            return repository.findByIdAndTenantId(id, principal.getTenantId())
                .map(existing -> {
                    TenantOidcConfig updated = new TenantOidcConfig(existing.id(), existing.tenantId(),
                        request.discoveryUri(), request.clientId(), request.clientSecret(),
                        existing.enabled(), existing.createdAt(), Instant.now(), existing.deletedAt());
                    return repository.save(updated);
                })
                .map(saved -> ResponseEntity.ok()
                    .header("X-Trace-Id", traceId)
                    .<ApiResponse<TenantOidcConfig>>body(new ApiResponse<>(saved, traceId)))
                .map(Mono::just)
                .orElseGet(() -> Mono.just(ResponseEntity.notFound()
                    .header("X-Trace-Id", traceId).build()));
        });
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal JwtAuthentication principal) {
        repository.softDeleteByIdAndTenantId(id, principal.getTenantId());
        return Mono.just(ResponseEntity.noContent().<Void>build());
    }
}
