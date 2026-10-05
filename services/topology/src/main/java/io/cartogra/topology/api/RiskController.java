package io.cartogra.topology.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.web.api.TracedResponse;
import io.cartogra.topology.api.dto.RiskPageResponse;
import io.cartogra.topology.domain.RiskPage;
import io.cartogra.topology.domain.RiskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/risks")
public class RiskController {

    private final RiskService riskService;

    public RiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<RiskPageResponse>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        RiskPage page = riskService.list(tenantId, limit, offset);
        return TracedResponse.ok(RiskPageResponse.from(page));
    }
}
