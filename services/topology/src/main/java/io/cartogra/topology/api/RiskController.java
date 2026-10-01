package io.cartogra.topology.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.topology.api.dto.RiskResponse;
import io.cartogra.topology.domain.Risk;
import io.cartogra.topology.domain.RiskService;
import io.opentelemetry.api.trace.Span;
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
    public ResponseEntity<ApiResponse<PageResult<RiskResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        PageResult<Risk> page = riskService.list(tenantId, limit, offset);
        PageResult<RiskResponse> mapped = PageResult.of(
                page.items().stream().map(RiskResponse::from).toList(), page.total(), page.limit(), page.offset());
        String traceId = traceId();
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(mapped, traceId));
    }

    private static String traceId() {
        return Span.current().getSpanContext().getTraceId();
    }
}
