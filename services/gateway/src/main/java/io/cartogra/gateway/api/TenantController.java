package io.cartogra.gateway.api;

import io.cartogra.gateway.api.dto.ApiResponse;
import io.cartogra.gateway.api.dto.TenantResponse;
import io.cartogra.gateway.domain.BillingPlanService;
import io.cartogra.gateway.domain.Tenant;
import io.cartogra.gateway.domain.TenantService;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<ApiResponse<TenantResponse>> getTenant(@AuthenticationPrincipal JwtClaims user) {
        String traceId = traceContext.currentTraceId();
        Tenant tenant = tenantService.getTenant(user.tenantId());
        long usersUsed = tenantService.countUsers(user.tenantId());
        TenantResponse result = TenantResponse.from(tenant, billingPlanService.getBySlug(tenant.plan()), usersUsed);
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(new ApiResponse<>(result, traceId));
    }
}
