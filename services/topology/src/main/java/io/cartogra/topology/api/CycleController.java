package io.cartogra.topology.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.web.api.TracedResponse;
import io.cartogra.topology.api.dto.CyclesResponse;
import io.cartogra.topology.domain.Cycles;
import io.cartogra.topology.domain.CycleService;
import io.cartogra.topology.domain.DependencyType;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/cycles")
public class CycleController {

    private final CycleService cycleService;

    public CycleController(CycleService cycleService) {
        this.cycleService = cycleService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CyclesResponse>> read(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) @Nullable DependencyType type) {
        Cycles result = cycleService.find(tenantId, type);
        return TracedResponse.ok(CyclesResponse.from(result));
    }
}
