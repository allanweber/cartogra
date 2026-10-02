package io.cartogra.topology.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.web.api.TracedResponse;
import io.cartogra.topology.api.dto.GraphResponse;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.domain.Graph;
import io.cartogra.topology.domain.GraphService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/graph")
public class GraphController {

    private final GraphService graphService;

    public GraphController(GraphService graphService) {
        this.graphService = graphService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<GraphResponse>> read(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) @Nullable UUID teamId,
            @RequestParam(required = false) @Nullable DependencyType type,
            @RequestParam(required = false) @Nullable Integer limit) {
        Graph graph = graphService.read(tenantId, teamId, type, limit);
        return TracedResponse.ok(GraphResponse.from(graph));
    }
}
