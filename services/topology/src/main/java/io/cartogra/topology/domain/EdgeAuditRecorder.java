package io.cartogra.topology.domain;

import io.cartogra.common.audit.AuditKind;
import io.cartogra.common.identity.Actor;
import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.topology.repository.GraphNodeRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Audits a dependency or drift change, naming both ends of the edge so the Timeline can show it. */
@Component
public class EdgeAuditRecorder {

    private final AuditEventPort auditEventPort;
    private final GraphNodeRepository graphNodeRepository;

    public EdgeAuditRecorder(AuditEventPort auditEventPort, GraphNodeRepository graphNodeRepository) {
        this.auditEventPort = auditEventPort;
        this.graphNodeRepository = graphNodeRepository;
    }

    public void record(UUID tenantId, AuditKind kind, UUID entityId, String action, Actor actor,
                       UUID sourceServiceId, UUID targetServiceId, Map<String, Object> extra) {
        Map<UUID, String> names = new LinkedHashMap<>();
        graphNodeRepository.findByServiceIds(tenantId, List.of(sourceServiceId, targetServiceId), 2)
                .forEach(node -> names.put(node.serviceId(), node.name()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceServiceId", sourceServiceId);
        payload.put("targetServiceId", targetServiceId);
        if (names.containsKey(sourceServiceId)) payload.put("sourceName", names.get(sourceServiceId));
        if (names.containsKey(targetServiceId)) payload.put("targetName", names.get(targetServiceId));
        payload.putAll(extra);
        auditEventPort.record(AuditEvent.of(tenantId, kind, entityId, action, actor, payload));
    }
}
