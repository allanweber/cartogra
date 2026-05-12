package io.cartogra.registry.api.controller;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.registry.api.dto.CreateScmConnectionRequest;
import io.cartogra.registry.api.dto.ScmConnectionResponse;
import io.cartogra.registry.api.dto.UpdateScmConnectionRequest;
import io.cartogra.registry.api.mapper.ScmConnectionMapper;
import io.cartogra.registry.application.dto.CreateScmConnectionCommand;
import io.cartogra.registry.application.dto.UpdateScmConnectionCommand;
import io.cartogra.registry.application.usecase.*;
import io.opentelemetry.api.trace.Span;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/scm-connections")
public class ScmConnectionController {

    private final CreateScmConnectionUseCase createConnection;
    private final UpdateScmConnectionUseCase updateConnection;
    private final DeleteScmConnectionUseCase deleteConnection;
    private final FindScmConnectionUseCase findConnection;
    private final ListScmConnectionsUseCase listConnections;

    public ScmConnectionController(
            CreateScmConnectionUseCase createConnection,
            UpdateScmConnectionUseCase updateConnection,
            DeleteScmConnectionUseCase deleteConnection,
            FindScmConnectionUseCase findConnection,
            ListScmConnectionsUseCase listConnections) {
        this.createConnection = createConnection;
        this.updateConnection = updateConnection;
        this.deleteConnection = deleteConnection;
        this.findConnection = findConnection;
        this.listConnections = listConnections;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ScmConnectionResponse>> create(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @Valid @RequestBody CreateScmConnectionRequest req) {
        String traceId = Span.current().getSpanContext().getTraceId();
        var result = ScmConnectionMapper.toResponse(
                createConnection.execute(new CreateScmConnectionCommand(tenantId, req.provider(), req.config())));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<ScmConnectionResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String traceId = Span.current().getSpanContext().getTraceId();
        var page = listConnections.execute(tenantId, limit, offset);
        var mapped = PageResult.of(page.items().stream().map(ScmConnectionMapper::toResponse).toList(),
                page.total(), page.limit(), page.offset());
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(mapped, traceId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ScmConnectionResponse>> get(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        String traceId = Span.current().getSpanContext().getTraceId();
        var result = ScmConnectionMapper.toResponse(findConnection.execute(tenantId, id));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ScmConnectionResponse>> update(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateScmConnectionRequest req) {
        String traceId = Span.current().getSpanContext().getTraceId();
        var result = ScmConnectionMapper.toResponse(
                updateConnection.execute(new UpdateScmConnectionCommand(tenantId, id, req.provider(), req.config())));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        String traceId = Span.current().getSpanContext().getTraceId();
        deleteConnection.execute(tenantId, id);
        return ResponseEntity.noContent()
                .header("X-Trace-Id", traceId)
                .build();
    }
}
