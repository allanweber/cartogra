package io.cartogra.registry.api;

import io.cartogra.common.api.ApiResponse;
import io.cartogra.common.api.PageResult;
import io.cartogra.common.audit.ActorKind;
import io.cartogra.registry.api.dto.AuditEventResponse;
import io.cartogra.registry.domain.AuditEventService;
import io.cartogra.registry.repository.AuditEventFilter;
import io.cartogra.web.api.TracedResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Tenant-wide audit trail; readable by every role (ADR-0017 amendment) — it backs the Timeline. */
@RestController
@RequestMapping("/audit-events")
public class AuditEventController {

    private final AuditEventService service;

    public AuditEventController(AuditEventService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<AuditEventResponse>>> list(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @RequestParam(required = false) List<String> entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) String actorKind,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        var filter = new AuditEventFilter(entityType, entityId,
                actorKind != null ? ActorKind.fromDbValue(actorKind) : null, from, to);
        return TracedResponse.ok(service.list(tenantId, filter, limit, offset).map(AuditEventResponse::from));
    }
}
