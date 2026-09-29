package io.cartogra.topology.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.topology.api.dto.BlastRadiusResponse;
import io.cartogra.topology.domain.BlastRadius;
import io.cartogra.topology.domain.BlastRadiusDirection;
import io.cartogra.topology.domain.BlastRadiusService;
import io.opentelemetry.api.trace.Span;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/blast-radius")
public class BlastRadiusController {

    private final BlastRadiusService blastRadiusService;

    public BlastRadiusController(BlastRadiusService blastRadiusService) {
        this.blastRadiusService = blastRadiusService;
    }

    @GetMapping("/{serviceId}")
    public ResponseEntity<ApiResponse<BlastRadiusResponse>> read(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID serviceId,
            @RequestParam(required = false) @Nullable BlastRadiusDirection direction,
            @RequestParam(required = false) @Nullable Integer depth) {
        BlastRadius result = blastRadiusService.compute(tenantId, serviceId, direction, depth);
        String traceId = traceId();
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(BlastRadiusResponse.from(result), traceId));
    }

    private static String traceId() {
        return Span.current().getSpanContext().getTraceId();
    }
}
