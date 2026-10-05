package io.cartogra.topology.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.web.api.TracedResponse;
import io.cartogra.topology.api.dto.ServiceDependenciesResponse;
import io.cartogra.topology.domain.DependencyService;
import io.cartogra.topology.domain.ServiceDependencies;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/services/{serviceId}/dependencies")
public class ServiceDependenciesController {

    private final DependencyService dependencyService;

    public ServiceDependenciesController(DependencyService dependencyService) {
        this.dependencyService = dependencyService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<ServiceDependenciesResponse>> read(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @PathVariable UUID serviceId) {
        ServiceDependencies dependencies = dependencyService.findForService(tenantId, serviceId);
        return TracedResponse.ok(ServiceDependenciesResponse.from(dependencies));
    }
}
