package io.cartogra.common.audit;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds audit payloads from an entity through a per-entity field allowlist, so a field not on the list
 * (tokens, kubeconfigs, password hashes, client secrets) can never reach {@code audit_events}.
 */
public final class AuditPayload {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private AuditPayload() {}

    /** Key fields of a created or deleted entity. */
    public static Map<String, Object> fields(ObjectMapper mapper, Object entity, Set<String> allowlist) {
        Map<String, Object> values = mapper.convertValue(entity, MAP_TYPE);
        Map<String, Object> result = new LinkedHashMap<>();
        allowlist.stream().sorted().forEach(field -> {
            Object value = values.get(field);
            if (value != null) {
                result.put(field, value);
            }
        });
        return result;
    }

    /** {@code {changes: {field: {from, to}}}} over allowlisted fields that differ; empty when none do. */
    public static Map<String, Object> changes(ObjectMapper mapper, Object before, Object after, Set<String> allowlist) {
        Map<String, Object> from = mapper.convertValue(before, MAP_TYPE);
        Map<String, Object> to = mapper.convertValue(after, MAP_TYPE);
        Map<String, Object> changes = new LinkedHashMap<>();
        allowlist.stream().sorted().forEach(field -> {
            if (!Objects.equals(from.get(field), to.get(field))) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", from.get(field));
                change.put("to", to.get(field));
                changes.put(field, change);
            }
        });
        if (changes.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changes", changes);
        return result;
    }

    /** Copy of {@code payload} with {@code key → value} added; null values are dropped. */
    public static Map<String, Object> with(Map<String, Object> payload, String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>(payload);
        if (value != null) {
            result.put(key, value);
        }
        return result;
    }
}
