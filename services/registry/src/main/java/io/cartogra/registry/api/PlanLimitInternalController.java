package io.cartogra.registry.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.registry.api.dto.PlanLimitsResponse;
import io.cartogra.registry.domain.PlanLimitService;
import io.opentelemetry.api.trace.Span;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal, service-to-service endpoint — reached directly by ingestion (not proxied
 * through the Gateway) so it can read plan limits owned by registry's schema. Not
 * client-facing; registry's SecurityConfig permits all traffic and relies on the
 * ClusterIP network boundary, same trust model as every other endpoint here.
 */
@RestController
@RequestMapping("/internal/plan-limits")
public class PlanLimitInternalController {

    private final PlanLimitService planLimitService;

    public PlanLimitInternalController(PlanLimitService planLimitService) {
        this.planLimitService = planLimitService;
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<ApiResponse<PlanLimitsResponse>> get(@PathVariable UUID tenantId) {
        String traceId = Span.current().getSpanContext().getTraceId();
        PlanLimitsResponse result = PlanLimitsResponse.from(planLimitService.getLimits(tenantId));
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }
}
