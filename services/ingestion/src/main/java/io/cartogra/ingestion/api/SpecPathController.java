package io.cartogra.ingestion.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.identity.Actor;
import io.cartogra.ingestion.api.dto.SpecPathsRequest;
import io.cartogra.ingestion.api.dto.SpecPathsResponse;
import io.cartogra.ingestion.domain.SpecPathService;
import io.cartogra.web.api.TracedResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/spec-paths")
public class SpecPathController {

    private final SpecPathService service;

    public SpecPathController(SpecPathService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<SpecPathsResponse>> get(@RequestHeader("X-Tenant-Id") UUID tenantId) {
        return TracedResponse.ok(SpecPathsResponse.from(service.get(tenantId)));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<SpecPathsResponse>> update(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @AuthenticationPrincipal Actor actor,
            @RequestBody SpecPathsRequest request) {
        return TracedResponse.ok(SpecPathsResponse.from(service.update(tenantId, actor, request)));
    }
}
