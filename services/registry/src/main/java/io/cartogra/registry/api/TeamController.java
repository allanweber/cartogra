package io.cartogra.registry.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.registry.api.dto.CreateTeamRequest;
import io.cartogra.registry.api.dto.TeamResponse;
import io.cartogra.registry.api.dto.UpdateTeamRequest;
import io.cartogra.registry.domain.TeamService;
import io.opentelemetry.api.trace.Span;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/teams")
public class TeamController {

    private final TeamService service;

    public TeamController(TeamService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TeamResponse>> create(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @Valid @RequestBody CreateTeamRequest req) {
        String traceId = traceId();
        var result = TeamResponse.from(service.create(tenantId, req.name()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<TeamResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String traceId = traceId();
        var page = service.list(tenantId, limit, offset);
        var mapped = PageResult.of(page.items().stream().map(TeamResponse::from).toList(),
                page.total(), page.limit(), page.offset());
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(mapped, traceId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TeamResponse>> get(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        String traceId = traceId();
        var result = TeamResponse.from(service.get(tenantId, id));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TeamResponse>> update(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTeamRequest req) {
        String traceId = traceId();
        var result = TeamResponse.from(service.update(tenantId, id, req.name()));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        String traceId = traceId();
        service.delete(tenantId, id);
        return ResponseEntity.noContent()
                .header("X-Trace-Id", traceId)
                .build();
    }

    private static String traceId() {
        return Span.current().getSpanContext().getTraceId();
    }
}
