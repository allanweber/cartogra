package io.cartogra.common.audit;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every audited entity kind and what a guest may see of it (ADR-0019 §8). Guest visibility is an allowlist:
 * a kind not listed here, or a payload field not in {@code guestFields}, never reaches a guest, so a producer
 * adding a field cannot leak it by accident. Settings kinds have no guest fields and are hidden entirely.
 */
public enum AuditKind {
    SERVICE("service", Set.of(
            "name", "description", "teamId", "repositoryUrl", "techStack", "tier", "tags", "slaTarget",
            "documentationUrl", "runbookUrl", "healthEndpoint", "source", "k8sCluster", "k8sNamespace",
            "k8sDeployment")),
    TEAM("team", Set.of("name")),
    DEPENDENCY("dependency", Set.of("sourceServiceId", "targetServiceId", "sourceName", "targetName", "protocol")),
    DRIFT("drift", Set.of("sourceServiceId", "targetServiceId", "sourceName", "targetName", "type", "resolution")),
    SCM_CONNECTION("scm_connection", Set.of(
            "provider", "syncScheduler", "pollIntervalMinutes", "webhookEnabled", "configChanged",
            "repositoriesSynced")),
    CLUSTER("cluster", Set.of()),
    USER("user", Set.of()),
    INVITATION("invitation", Set.of()),
    OIDC_CONFIG("oidc_config", Set.of());

    private static final String CHANGES = "changes";

    private final String entityType;
    private final Set<String> guestFields;

    AuditKind(String entityType, Set<String> guestFields) {
        this.entityType = entityType;
        this.guestFields = guestFields;
    }

    public String entityType() {
        return entityType;
    }

    public boolean guestVisible() {
        return !guestFields.isEmpty();
    }

    public static Optional<AuditKind> fromEntityType(String entityType) {
        return Arrays.stream(values()).filter(kind -> kind.entityType.equals(entityType)).findFirst();
    }

    public static List<String> guestVisibleEntityTypes() {
        return Arrays.stream(values()).filter(AuditKind::guestVisible).map(AuditKind::entityType).toList();
    }

    /** The payload with only {@code guestFields}, both at the top level and inside {@code changes}. */
    public Map<String, Object> guestPayload(Map<String, Object> payload) {
        Map<String, Object> visible = new LinkedHashMap<>();
        payload.forEach((key, value) -> {
            if (guestFields.contains(key)) {
                visible.put(key, value);
            } else if (CHANGES.equals(key) && value instanceof Map<?, ?> changes) {
                Map<String, Object> visibleChanges = new LinkedHashMap<>();
                changes.forEach((field, change) -> {
                    if (guestFields.contains(String.valueOf(field))) {
                        visibleChanges.put(String.valueOf(field), change);
                    }
                });
                if (!visibleChanges.isEmpty()) {
                    visible.put(CHANGES, visibleChanges);
                }
            }
        });
        return visible;
    }
}
