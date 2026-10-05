package io.cartogra.topology.domain;

import io.cartogra.topology.repository.DependencyGraphViewRepository;
import io.cartogra.topology.repository.FanInRow;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.TenantTopologySettingsRepository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Flags services with no redundancy that sit on the critical path of many others: fan-in
 * (distinct dependents, declared and observed combined) at or above a tenant-configurable
 * threshold. Severity escalates to {@link RiskSeverity#CRITICAL} for a {@code CRITICAL}-tier or
 * unowned (orphan) service — see {@link #severityOf}.
 */
@org.springframework.stereotype.Service
public class SpofService {

    public static final int DEFAULT_THRESHOLD = 5;
    public static final int MAX_ROWS = 200;

    private final TenantTopologySettingsRepository settingsRepository;
    private final DependencyGraphViewRepository graphViewRepository;
    private final GraphNodeRepository graphNodeRepository;

    public SpofService(TenantTopologySettingsRepository settingsRepository,
            DependencyGraphViewRepository graphViewRepository, GraphNodeRepository graphNodeRepository) {
        this.settingsRepository = settingsRepository;
        this.graphViewRepository = graphViewRepository;
        this.graphNodeRepository = graphNodeRepository;
    }

    public SpofResult detect(UUID tenantId) {
        int threshold = Math.max(1, settingsRepository.findSpofThreshold(tenantId).orElse(DEFAULT_THRESHOLD));
        List<FanInRow> fanInRows = graphViewRepository.findFanIn(tenantId, threshold, MAX_ROWS + 1);

        Set<UUID> ids = fanInRows.stream().map(FanInRow::serviceId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, GraphNode> nodesById = graphNodeRepository.findByServiceIds(tenantId, ids, ids.size()).stream()
                .collect(Collectors.toMap(GraphNode::serviceId, Function.identity()));

        List<Spof> live = fanInRows.stream()
                .filter(row -> nodesById.get(row.serviceId()) != null)
                .map(row -> {
                    GraphNode node = nodesById.get(row.serviceId());
                    return new Spof(node, row.fanIn(), severityOf(node));
                })
                .toList();
        boolean truncated = live.size() > MAX_ROWS;
        List<Spof> items = truncated ? live.subList(0, MAX_ROWS) : live;

        return new SpofResult(threshold, rationale(threshold), items, truncated);
    }

    static RiskSeverity severityOf(GraphNode node) {
        boolean critical = "CRITICAL".equals(node.tier()) || node.teamId() == null;
        return critical ? RiskSeverity.CRITICAL : RiskSeverity.WARNING;
    }

    private static String rationale(int threshold) {
        return ("Services with %d or more direct dependents are flagged as single points of failure; "
                + "severity escalates to CRITICAL for CRITICAL-tier or unowned (orphan) services.").formatted(threshold);
    }
}
