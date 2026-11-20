package io.cartogra.registry.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.registry.domain.ServiceService;
import io.cartogra.registry.domain.exception.ServiceNotFoundException;
import io.cartogra.web.api.TracedResponse;
import io.cartogra.web.tenant.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Internal, service-to-service endpoint (denied at the Gateway, see {@code InternalPathDenyIT}): Contract finds the
 * service of a discovered spec by its SCM external id. A deleted or cross-tenant service gives 404.
 */
@RestController
@RequestMapping("/internal/services/by-external-id")
public class ServiceByExternalIdInternalController {

    private final ServiceService service;

    public ServiceByExternalIdInternalController(ServiceService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, UUID>>> find(@RequestParam UUID tenantId,
                                                               @RequestParam String externalId) {
        UUID serviceId = TenantContext.callAs(tenantId, () -> service.findIdByExternalId(tenantId, externalId))
                .orElseThrow(() -> new ServiceNotFoundException(externalId));
        return TracedResponse.ok(Map.of("serviceId", serviceId));
    }
}
