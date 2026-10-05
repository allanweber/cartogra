package io.cartogra.registry.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.registry.api.dto.AddTeamMemberRequest;
import io.cartogra.registry.api.dto.CreateTeamRequest;
import io.cartogra.registry.api.dto.TeamMemberResponse;
import io.cartogra.registry.api.dto.TeamResponse;
import io.cartogra.registry.api.dto.UpdateTeamRequest;
import io.cartogra.registry.domain.TeamService;
import io.cartogra.web.api.TracedResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
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
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @Valid @RequestBody CreateTeamRequest req) {
        var result = TeamResponse.from(service.create(tenantId, req.name(), userId));
        return TracedResponse.created(result);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<TeamResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        var page = service.list(tenantId, limit, offset);
        var mapped = PageResult.of(page.items().stream().map(TeamResponse::from).toList(),
                page.total(), page.limit(), page.offset());
        return TracedResponse.ok(mapped);
    }

    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<Set<UUID>>> mine(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId) {
        var result = service.myTeamIds(tenantId, userId);
        return TracedResponse.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TeamResponse>> get(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        var result = TeamResponse.from(service.get(tenantId, id));
        return TracedResponse.ok(result);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TeamResponse>> update(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTeamRequest req) {
        var result = TeamResponse.from(service.update(tenantId, id, req.name(), userId));
        return TracedResponse.ok(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID id) {
        service.delete(tenantId, id, userId);
        return TracedResponse.noContent();
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<ApiResponse<List<TeamMemberResponse>>> listMembers(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID id) {
        var result = service.listMembers(tenantId, id).stream().map(TeamMemberResponse::from).toList();
        return TracedResponse.ok(result);
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<ApiResponse<TeamMemberResponse>> addMember(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID id,
            @Valid @RequestBody AddTeamMemberRequest req) {
        var result = TeamMemberResponse.from(service.addMember(tenantId, id, req.userId(), userId));
        return TracedResponse.created(result);
    }

    @DeleteMapping("/{id}/members/{memberUserId}")
    public ResponseEntity<Void> removeMember(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID id,
            @PathVariable UUID memberUserId) {
        service.removeMember(tenantId, id, memberUserId, userId);
        return TracedResponse.noContent();
    }
}
