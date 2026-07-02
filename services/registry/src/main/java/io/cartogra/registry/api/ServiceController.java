package io.cartogra.registry.api;

import io.cartogra.registry.api.dto.AssignOwnerRequest;
import io.cartogra.registry.api.dto.CreateServiceRequest;
import io.cartogra.registry.api.dto.ServiceResponse;
import io.cartogra.registry.api.dto.ServiceSnapshotResponse;
import io.cartogra.registry.api.dto.UpdateServiceRequest;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.registry.repository.ServiceFilter;
import io.cartogra.registry.domain.ServiceService;
import io.cartogra.registry.domain.exception.ServiceNotFoundException;
import io.opentelemetry.api.trace.Span;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.cartogra.registry.api.dto.ConnectionServiceCountsResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    private final ServiceService service;

    public ServiceController(ServiceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ServiceResponse>> create(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @Valid @RequestBody CreateServiceRequest req) {
        String traceId = traceId();
        var result = ServiceResponse.from(service.create(tenantId, req, userId));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<ServiceResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(defaultValue = "false") boolean unowned,
            @RequestParam(required = false) String health,
            @RequestParam(required = false) List<String> techStack,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String traceId = traceId();
        var filter = new ServiceFilter(teamId, unowned, health, techStack, search, source);
        var page = service.list(tenantId, filter, limit, offset);
        var mapped = PageResult.of(page.items().stream().map(ServiceResponse::from).toList(),
                page.total(), page.limit(), page.offset());
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(mapped, traceId));
    }

    @GetMapping("/counts-by-connection")
    public ResponseEntity<ApiResponse<ConnectionServiceCountsResponse>> countsByConnection(
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        String traceId = traceId();
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(ConnectionServiceCountsResponse.from(service.countByConnectionId(tenantId)), traceId));
    }

    @GetMapping("/tech-stacks")
    public ResponseEntity<ApiResponse<List<String>>> techStacks(
            @RequestHeader("X-Tenant-Id") UUID tenantId) {
        String traceId = traceId();
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(service.listTechStacks(tenantId), traceId));
    }

    @GetMapping("/orphaned")
    public ResponseEntity<ApiResponse<PageResult<ServiceResponse>>> orphaned(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String traceId = traceId();
        var page = service.detectOrphans(tenantId, limit, offset);
        var mapped = PageResult.of(page.items().stream().map(ServiceResponse::from).toList(),
                page.total(), page.limit(), page.offset());
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(mapped, traceId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ServiceResponse>> get(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        String traceId = traceId();
        var result = ServiceResponse.from(service.get(tenantId, id));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ServiceResponse>> update(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateServiceRequest req) {
        String traceId = traceId();
        var result = ServiceResponse.from(service.update(tenantId, id, req, userId));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID id) {
        String traceId = traceId();
        service.delete(tenantId, id, userId);
        return ResponseEntity.noContent()
                .header("X-Trace-Id", traceId)
                .build();
    }

    @PatchMapping("/{id}/owner")
    public ResponseEntity<ApiResponse<ServiceResponse>> assignOwner(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID id,
            @RequestBody AssignOwnerRequest req) {
        String traceId = traceId();
        var result = ServiceResponse.from(service.assignOwner(tenantId, id, req.teamId(), userId));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @DeleteMapping("/{id}/owner")
    public ResponseEntity<ApiResponse<ServiceResponse>> removeOwner(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID id) {
        String traceId = traceId();
        var result = ServiceResponse.from(service.assignOwner(tenantId, id, null, userId));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<ApiResponse<PageResult<ServiceSnapshotResponse>>> history(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id,
            @RequestParam(required = false) Instant at,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String traceId = traceId();
        if (at != null) {
            var snap = service.historyAt(tenantId, id, at)
                    .map(ServiceSnapshotResponse::from)
                    .orElseThrow(() -> new ServiceNotFoundException(id));
            return ResponseEntity.ok()
                    .header("X-Trace-Id", traceId)
                    .body(new ApiResponse<>(PageResult.of(List.of(snap), 1L, 1, 0), traceId));
        }
        var page = service.history(tenantId, id, limit, offset);
        var mapped = PageResult.of(page.items().stream().map(ServiceSnapshotResponse::from).toList(),
                page.total(), page.limit(), page.offset());
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(mapped, traceId));
    }

    private static String traceId() {
        return Span.current().getSpanContext().getTraceId();
    }
}
