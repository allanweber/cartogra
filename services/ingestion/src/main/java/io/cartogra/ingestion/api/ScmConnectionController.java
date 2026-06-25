package io.cartogra.ingestion.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.ingestion.api.dto.ScmConnectionRequest;
import io.cartogra.ingestion.api.dto.ScmConnectionResponse;
import io.cartogra.ingestion.domain.ScmConnectionService;
import io.opentelemetry.api.trace.Span;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/scm-connections")
public class ScmConnectionController {

    private final ScmConnectionService service;

    public ScmConnectionController(ScmConnectionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ScmConnectionResponse>> create(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @Valid @RequestBody ScmConnectionRequest req) {
        String traceId = traceId();
        var result = ScmConnectionResponse.from(service.create(tenantId, req));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<ScmConnectionResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String traceId = traceId();
        var page = service.list(tenantId, limit, offset);
        var mapped = PageResult.of(page.items().stream().map(ScmConnectionResponse::from).toList(),
                page.total(), page.limit(), page.offset());
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(mapped, traceId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ScmConnectionResponse>> get(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        String traceId = traceId();
        var result = ScmConnectionResponse.from(service.get(tenantId, id));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ScmConnectionResponse>> update(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id,
            @Valid @RequestBody ScmConnectionRequest req) {
        String traceId = traceId();
        var result = ScmConnectionResponse.from(service.update(tenantId, id, req));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        service.delete(tenantId, id);
        return ResponseEntity.noContent()
                .header("X-Trace-Id", traceId())
                .build();
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<Void> triggerSync(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        service.triggerSync(tenantId, id);
        return ResponseEntity.accepted()
                .header("X-Trace-Id", traceId())
                .build();
    }

    private static String traceId() {
        return Span.current().getSpanContext().getTraceId();
    }
}
