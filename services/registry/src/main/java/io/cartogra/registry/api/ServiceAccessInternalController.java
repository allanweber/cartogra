package io.cartogra.registry.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.registry.api.dto.ServiceAccessRequest;
import io.cartogra.registry.domain.ServiceService;
import io.opentelemetry.api.trace.Span;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Internal, service-to-service endpoint — reached directly by Topology (not proxied through
 * the Gateway; see {@code InternalPathDenyIT}), same trust model as {@link PlanLimitInternalController}.
 * Backs Topology's declared-dependency authorization check: "is this user a member of the team
 * owning this service?" Unlike {@link ServiceInternalController}, this IS tenant-scoped — an
 * unknown or cross-tenant serviceId resolves to {@code false} rather than leaking across tenants.
 */
@RestController
@RequestMapping("/internal/services/access")
public class ServiceAccessInternalController {

    private final ServiceService service;

    public ServiceAccessInternalController(ServiceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<UUID, Boolean>>> checkAccess(@Valid @RequestBody ServiceAccessRequest request) {
        String traceId = Span.current().getSpanContext().getTraceId();
        Map<UUID, Boolean> result = new LinkedHashMap<>();
        for (UUID serviceId : request.serviceIds()) {
            result.put(serviceId, service.isAccessibleBy(request.tenantId(), request.userId(), serviceId));
        }
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }
}
