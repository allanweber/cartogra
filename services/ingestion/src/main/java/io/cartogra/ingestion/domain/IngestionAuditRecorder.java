package io.cartogra.ingestion.domain;

import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.common.audit.AuditKind;
import io.cartogra.common.audit.AuditPayload;
import io.cartogra.common.identity.Actor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Audits SCM connection and cluster changes. The allowlists exclude {@code config} (encrypted tokens, webhook
 * secrets), CA certs, SA tokens and kubeconfigs: a credential change shows as a flag, never as a value.
 */
@Component
public class IngestionAuditRecorder {

    static final Set<String> SCM_CONNECTION_FIELDS =
            Set.of("provider", "syncScheduler", "pollIntervalMinutes", "webhookEnabled");
    static final Set<String> CLUSTER_FIELDS = Set.of("name", "source", "apiServerUrl", "skipTlsVerify");

    private final AuditEventPort auditEventPort;
    private final ObjectMapper objectMapper;

    public IngestionAuditRecorder(AuditEventPort auditEventPort, ObjectMapper objectMapper) {
        this.auditEventPort = auditEventPort;
        this.objectMapper = objectMapper;
    }

    public void scmConnectionCreated(Actor actor, ScmConnection connection) {
        record(actor, connection.tenantId(), AuditKind.SCM_CONNECTION, connection.id(), "created",
                AuditPayload.fields(objectMapper, connection, SCM_CONNECTION_FIELDS));
    }

    public void scmConnectionUpdated(Actor actor, ScmConnection before, ScmConnection after) {
        Map<String, Object> payload = AuditPayload.changes(objectMapper, before, after, SCM_CONNECTION_FIELDS);
        if (!before.config().equals(after.config())) {
            payload = AuditPayload.with(payload, "configChanged", true);
        }
        if (!payload.isEmpty()) {
            record(actor, after.tenantId(), AuditKind.SCM_CONNECTION, after.id(), "updated",
                    AuditPayload.with(payload, "provider", after.provider()));
        }
    }

    public void scmConnectionDeleted(Actor actor, ScmConnection connection) {
        record(actor, connection.tenantId(), AuditKind.SCM_CONNECTION, connection.id(), "deleted",
                AuditPayload.fields(objectMapper, connection, SCM_CONNECTION_FIELDS));
    }

    public void syncFinished(UUID tenantId, UUID connectionId, String provider, boolean succeeded,
                             int repositoriesSynced, String error) {
        Map<String, Object> payload = AuditPayload.with(Map.of("provider", provider),
                succeeded ? "repositoriesSynced" : "error",
                succeeded ? repositoriesSynced : truncate(error));
        auditEventPort.record(AuditEvent.of(tenantId, AuditKind.SCM_CONNECTION, connectionId,
                succeeded ? "sync_completed" : "sync_failed", Actor.SYSTEM, payload));
    }

    public void clusterCreated(Actor actor, Cluster cluster) {
        record(actor, cluster.tenantId(), AuditKind.CLUSTER, cluster.id(), "created",
                AuditPayload.fields(objectMapper, cluster, CLUSTER_FIELDS));
    }

    public void clusterUpdated(Actor actor, Cluster before, Cluster after) {
        Map<String, Object> payload = AuditPayload.changes(objectMapper, before, after, CLUSTER_FIELDS);
        boolean credentialsChanged = !Objects.equals(before.caCertPem(), after.caCertPem())
                || !Objects.equals(before.saToken(), after.saToken())
                || !Objects.equals(before.kubeconfigEncrypted(), after.kubeconfigEncrypted());
        if (credentialsChanged) {
            payload = AuditPayload.with(payload, "credentialsChanged", true);
        }
        if (!payload.isEmpty()) {
            record(actor, after.tenantId(), AuditKind.CLUSTER, after.id(), "updated",
                    AuditPayload.with(payload, "name", after.name()));
        }
    }

    public void clusterDeleted(Actor actor, Cluster cluster) {
        record(actor, cluster.tenantId(), AuditKind.CLUSTER, cluster.id(), "deleted",
                AuditPayload.fields(objectMapper, cluster, CLUSTER_FIELDS));
    }

    private void record(Actor actor, UUID tenantId, AuditKind kind, UUID entityId, String action,
                        Map<String, Object> payload) {
        auditEventPort.record(AuditEvent.of(tenantId, kind, entityId, action, actor, payload));
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
