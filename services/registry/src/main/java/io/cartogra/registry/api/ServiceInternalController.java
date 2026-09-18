package io.cartogra.registry.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.registry.api.dto.ServiceResponse;
import io.cartogra.registry.domain.ServiceService;
import io.opentelemetry.api.trace.Span;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal, service-to-service endpoint — reached directly by other services (not proxied
 * through the Gateway; see {@code InternalPathDenyIT}), same trust model as
 * {@link PlanLimitInternalController}. Cross-tenant on purpose: Topology's admin backfill
 * (POST /internal/backfill on Topology) walks every tenant's services once to seed
 * {@code graph_nodes} for tenants that predate its Kafka consumer.
 */
@RestController
@RequestMapping("/internal/services")
public class ServiceInternalController {

    private final ServiceService service;

    public ServiceInternalController(ServiceService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<ServiceResponse>>> listAllActive(
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String traceId = Span.current().getSpanContext().getTraceId();
        var page = service.listAllActive(limit, offset);
        var mapped = PageResult.of(page.items().stream().map(ServiceResponse::from).toList(),
                page.total(), page.limit(), page.offset());
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(mapped, traceId));
    }
}
