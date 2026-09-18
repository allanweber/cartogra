package io.cartogra.topology.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.topology.api.dto.BackfillResponse;
import io.cartogra.topology.domain.GraphNodeService;
import io.opentelemetry.api.trace.Span;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin, service-to-service endpoint — not proxied through the Gateway (see registry's
 * {@code InternalPathDenyIT} for the pattern this follows). Walks Registry once via
 * {@link GraphNodeService#backfill()} to seed {@code graph_nodes} for tenants that predate
 * the {@code GraphNodeEventConsumer} — a one-off, safe to re-run.
 */
@RestController
@RequestMapping("/internal/backfill")
public class BackfillInternalController {

    private final GraphNodeService graphNodeService;

    public BackfillInternalController(GraphNodeService graphNodeService) {
        this.graphNodeService = graphNodeService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BackfillResponse>> backfill() {
        String traceId = Span.current().getSpanContext().getTraceId();
        int upserted = graphNodeService.backfill();
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(new BackfillResponse(upserted), traceId));
    }
}
