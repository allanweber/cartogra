package io.cartogra.ingestion.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.ingestion.api.dto.KubernetesClusterRequest;
import io.cartogra.ingestion.api.dto.KubernetesClusterResponse;
import io.cartogra.ingestion.domain.ClusterService;
import io.opentelemetry.api.trace.Span;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/k8s/clusters")
public class KubernetesClusterController {

    private final ClusterService clusterService;

    public KubernetesClusterController(ClusterService clusterService) {
        this.clusterService = clusterService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<KubernetesClusterResponse>> create(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @Valid @RequestBody KubernetesClusterRequest request) {
        String traceId = Span.current().getSpanContext().getTraceId();
        KubernetesClusterResponse result = KubernetesClusterResponse.from(clusterService.create(tenantId, request));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<KubernetesClusterResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String traceId = Span.current().getSpanContext().getTraceId();
        var page = clusterService.list(tenantId, limit, offset);
        PageResult<KubernetesClusterResponse> result = PageResult.of(
                page.items().stream().map(KubernetesClusterResponse::from).toList(),
                page.total(), page.limit(), page.offset());
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<KubernetesClusterResponse>> get(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        String traceId = Span.current().getSpanContext().getTraceId();
        KubernetesClusterResponse result = KubernetesClusterResponse.from(clusterService.get(tenantId, id));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<KubernetesClusterResponse>> update(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id,
            @Valid @RequestBody KubernetesClusterRequest request) {
        String traceId = Span.current().getSpanContext().getTraceId();
        KubernetesClusterResponse result = KubernetesClusterResponse.from(clusterService.update(tenantId, id, request));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        clusterService.delete(tenantId, id);
        return ResponseEntity.noContent().build();
    }
}
