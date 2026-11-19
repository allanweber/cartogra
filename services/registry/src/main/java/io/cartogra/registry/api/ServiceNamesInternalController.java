package io.cartogra.registry.api;

import io.cartogra.web.tenant.TenantContext;
import io.cartogra.common.api.ApiResponse;
import io.cartogra.registry.api.dto.ServiceNamesRequest;
import io.cartogra.registry.domain.ServiceService;
import io.cartogra.web.api.TracedResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Internal, service-to-service endpoint (denied at the Gateway, see {@code InternalPathDenyIT}): Contract asks for
 * the names of services it has no name for yet. Tenant-scoped; unknown, deleted or cross-tenant ids are left out.
 */
@RestController
@RequestMapping("/internal/services/names")
public class ServiceNamesInternalController {

    private final ServiceService service;

    public ServiceNamesInternalController(ServiceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<UUID, String>>> names(@Valid @RequestBody ServiceNamesRequest request) {
        return TracedResponse.ok(TenantContext.callAs(request.tenantId(),
                () -> service.findNames(request.tenantId(), request.serviceIds())));
    }
}
