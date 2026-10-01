package io.cartogra.topology.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.topology.api.dto.SpofsResponse;
import io.cartogra.topology.domain.SpofResult;
import io.cartogra.topology.domain.SpofService;
import io.opentelemetry.api.trace.Span;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/spofs")
public class SpofController {

    private final SpofService spofService;

    public SpofController(SpofService spofService) {
        this.spofService = spofService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<SpofsResponse>> read(@RequestHeader("X-Tenant-Id") UUID tenantId) {
        SpofResult result = spofService.detect(tenantId);
        String traceId = traceId();
        return ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(SpofsResponse.from(result), traceId));
    }

    private static String traceId() {
        return Span.current().getSpanContext().getTraceId();
    }
}
