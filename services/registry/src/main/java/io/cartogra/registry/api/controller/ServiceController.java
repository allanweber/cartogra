package io.cartogra.registry.api.controller;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.registry.api.dto.*;
import io.cartogra.registry.api.mapper.ServiceMapper;
import io.cartogra.registry.application.dto.*;
import io.cartogra.registry.application.usecase.*;
import io.opentelemetry.api.trace.Span;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    private final CreateServiceUseCase createService;
    private final UpdateServiceUseCase updateService;
    private final DeleteServiceUseCase deleteService;
    private final FindServiceUseCase findService;
    private final ListServicesUseCase listServices;
    private final AssignOwnerUseCase assignOwner;
    private final DetectOrphansUseCase detectOrphans;
    private final GetServiceHistoryUseCase getServiceHistory;

    public ServiceController(
            CreateServiceUseCase createService,
            UpdateServiceUseCase updateService,
            DeleteServiceUseCase deleteService,
            FindServiceUseCase findService,
            ListServicesUseCase listServices,
            AssignOwnerUseCase assignOwner,
            DetectOrphansUseCase detectOrphans,
            GetServiceHistoryUseCase getServiceHistory) {
        this.createService = createService;
        this.updateService = updateService;
        this.deleteService = deleteService;
        this.findService = findService;
        this.listServices = listServices;
        this.assignOwner = assignOwner;
        this.detectOrphans = detectOrphans;
        this.getServiceHistory = getServiceHistory;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ServiceResponse>> create(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @Valid @RequestBody CreateServiceRequest req) {
        String traceId = Span.current().getSpanContext().getTraceId();
        var cmd = new CreateServiceCommand(tenantId, req.name(), req.description(),
                req.teamId(), req.repositoryUrl(), req.techStack(), req.metadata(), userId);
        var result = ServiceMapper.toResponse(createService.execute(cmd));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<ServiceResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) String health,
            @RequestParam(required = false) String techStack,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String traceId = Span.current().getSpanContext().getTraceId();
        var filter = new ServiceFilter(teamId, health, techStack, search);
        var page = listServices.execute(tenantId, filter, limit, offset);
        var mapped = PageResult.of(page.items().stream().map(ServiceMapper::toResponse).toList(),
                page.total(), page.limit(), page.offset());
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(mapped, traceId));
    }

    @GetMapping("/orphaned")
    public ResponseEntity<ApiResponse<PageResult<ServiceResponse>>> orphaned(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String traceId = Span.current().getSpanContext().getTraceId();
        var page = detectOrphans.execute(tenantId, limit, offset);
        var mapped = PageResult.of(page.items().stream().map(ServiceMapper::toResponse).toList(),
                page.total(), page.limit(), page.offset());
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(mapped, traceId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ServiceResponse>> get(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        String traceId = Span.current().getSpanContext().getTraceId();
        var result = ServiceMapper.toResponse(findService.execute(tenantId, id));
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
        String traceId = Span.current().getSpanContext().getTraceId();
        var cmd = new UpdateServiceCommand(tenantId, id, req.name(), req.description(),
                req.repositoryUrl(), req.techStack(), req.metadata(), req.healthStatus(), userId);
        var result = ServiceMapper.toResponse(updateService.execute(cmd));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID id) {
        String traceId = Span.current().getSpanContext().getTraceId();
        deleteService.execute(tenantId, id, userId);
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
        String traceId = Span.current().getSpanContext().getTraceId();
        var cmd = new AssignOwnerCommand(tenantId, id, req.teamId(), userId);
        var result = ServiceMapper.toResponse(assignOwner.execute(cmd));
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
        String traceId = Span.current().getSpanContext().getTraceId();
        if (at != null) {
            var snap = getServiceHistory.atPointInTime(tenantId, id, at)
                    .map(ServiceMapper::toSnapshotResponse)
                    .orElseThrow(() -> new io.cartogra.registry.domain.exception.ServiceNotFoundException(id));
            return ResponseEntity.ok()
                    .header("X-Trace-Id", traceId)
                    .body(new ApiResponse<>(PageResult.of(java.util.List.of(snap), 1L, 1, 0), traceId));
        }
        var page = getServiceHistory.execute(tenantId, id, limit, offset);
        var mapped = PageResult.of(page.items().stream().map(ServiceMapper::toSnapshotResponse).toList(),
                page.total(), page.limit(), page.offset());
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(mapped, traceId));
    }
}
