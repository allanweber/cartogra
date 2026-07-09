package io.cartogra.gateway.api;

import io.cartogra.gateway.api.dto.ApiResponse;
import io.cartogra.gateway.api.dto.TenantResponse;
import io.cartogra.gateway.domain.BillingPlanService;
import io.cartogra.gateway.domain.Tenant;
import io.cartogra.gateway.domain.TenantService;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.infrastructure.security.JwtAuthentication;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class TenantController {

    private final TenantService tenantService;
    private final BillingPlanService billingPlanService;
    private final TraceContext traceContext;

    public TenantController(TenantService tenantService, BillingPlanService billingPlanService, TraceContext traceContext) {
        this.tenantService = tenantService;
        this.billingPlanService = billingPlanService;
        this.traceContext = traceContext;
    }

    @GetMapping("/tenant")
    public ResponseEntity<ApiResponse<TenantResponse>> getTenant() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthentication principal)) {
            throw new UnauthorizedException("Authentication required");
        }
        String traceId = traceContext.currentTraceId();
        Tenant tenant = tenantService.getTenant(principal.getTenantId());
        TenantResponse result = TenantResponse.from(tenant, billingPlanService.getBySlug(tenant.plan()));
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result, traceId));
    }
}
