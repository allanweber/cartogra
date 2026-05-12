package io.cartogra.registry.api.controller;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.registry.api.dto.CreateTeamRequest;
import io.cartogra.registry.api.dto.TeamResponse;
import io.cartogra.registry.api.dto.UpdateTeamRequest;
import io.cartogra.registry.api.mapper.TeamMapper;
import io.cartogra.registry.application.dto.CreateTeamCommand;
import io.cartogra.registry.application.dto.UpdateTeamCommand;
import io.cartogra.registry.application.usecase.*;
import io.opentelemetry.api.trace.Span;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    private final CreateTeamUseCase createTeam;
    private final UpdateTeamUseCase updateTeam;
    private final DeleteTeamUseCase deleteTeam;
    private final FindTeamUseCase findTeam;
    private final ListTeamsUseCase listTeams;

    public TeamController(
            CreateTeamUseCase createTeam,
            UpdateTeamUseCase updateTeam,
            DeleteTeamUseCase deleteTeam,
            FindTeamUseCase findTeam,
            ListTeamsUseCase listTeams) {
        this.createTeam = createTeam;
        this.updateTeam = updateTeam;
        this.deleteTeam = deleteTeam;
        this.findTeam = findTeam;
        this.listTeams = listTeams;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TeamResponse>> create(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @Valid @RequestBody CreateTeamRequest req) {
        String traceId = Span.current().getSpanContext().getTraceId();
        var result = TeamMapper.toResponse(createTeam.execute(new CreateTeamCommand(tenantId, req.name())));
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<TeamResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String traceId = Span.current().getSpanContext().getTraceId();
        var page = listTeams.execute(tenantId, limit, offset);
        var mapped = PageResult.of(page.items().stream().map(TeamMapper::toResponse).toList(),
                page.total(), page.limit(), page.offset());
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(mapped, traceId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TeamResponse>> get(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        String traceId = Span.current().getSpanContext().getTraceId();
        var result = TeamMapper.toResponse(findTeam.execute(tenantId, id));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TeamResponse>> update(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTeamRequest req) {
        String traceId = Span.current().getSpanContext().getTraceId();
        var result = TeamMapper.toResponse(updateTeam.execute(new UpdateTeamCommand(tenantId, id, req.name())));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        String traceId = Span.current().getSpanContext().getTraceId();
        deleteTeam.execute(tenantId, id);
        return ResponseEntity.noContent()
                .header("X-Trace-Id", traceId)
                .build();
    }
}
