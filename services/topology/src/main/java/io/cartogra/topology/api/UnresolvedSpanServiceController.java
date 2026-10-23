package io.cartogra.topology.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.topology.api.dto.UnresolvedSpanServiceResponse;
import io.cartogra.topology.domain.UnresolvedSpanServiceService;
import io.cartogra.web.api.TracedResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/unresolved-span-services")
public class UnresolvedSpanServiceController {

    private final UnresolvedSpanServiceService unresolvedSpanServiceService;

    public UnresolvedSpanServiceController(UnresolvedSpanServiceService unresolvedSpanServiceService) {
        this.unresolvedSpanServiceService = unresolvedSpanServiceService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<UnresolvedSpanServiceResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return TracedResponse.ok(
                unresolvedSpanServiceService.list(tenantId, limit, offset).map(UnresolvedSpanServiceResponse::from));
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<ApiResponse<UnresolvedSpanServiceResponse>> dismiss(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestHeader(value = "X-User-Id", required = false) UUID userId,
            @PathVariable UUID id) {
        return TracedResponse.ok(UnresolvedSpanServiceResponse.from(
                unresolvedSpanServiceService.dismiss(tenantId, userId, id)));
    }
}
