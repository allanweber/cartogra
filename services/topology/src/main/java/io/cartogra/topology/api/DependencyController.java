package io.cartogra.topology.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.topology.api.dto.DeclareDependencyRequest;
import io.cartogra.topology.api.dto.DependencyResponse;
import io.cartogra.topology.domain.DependencyService;
import io.opentelemetry.api.trace.Span;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/dependencies")
public class DependencyController {

    private final DependencyService dependencyService;

    public DependencyController(DependencyService dependencyService) {
        this.dependencyService = dependencyService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DependencyResponse>> create(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @Valid @RequestBody DeclareDependencyRequest request) {
        String traceId = traceId();
        DependencyResponse result = DependencyResponse.from(dependencyService.create(tenantId, userId, request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DependencyResponse>> update(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID id,
            @Valid @RequestBody DeclareDependencyRequest request) {
        String traceId = traceId();
        DependencyResponse result = DependencyResponse.from(dependencyService.update(tenantId, userId, id, request));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID id) {
        dependencyService.delete(tenantId, userId, id);
        return ResponseEntity.noContent()
                .header("X-Trace-Id", traceId())
                .build();
    }

    private static String traceId() {
        return Span.current().getSpanContext().getTraceId();
    }
}
