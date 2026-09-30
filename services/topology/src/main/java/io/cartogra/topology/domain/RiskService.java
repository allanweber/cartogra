package io.cartogra.topology.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.topology.repository.DependencyDriftRepository;
import io.cartogra.topology.repository.GraphNodeRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Aggregates cycle, SPOF, orphan, and drift findings from across the tenant's dependency graph
 * into one paginated, severity-sorted list. Each source stays independently computable —
 * this service only combines, sorts, and paginates.
 */
@org.springframework.stereotype.Service
public class RiskService {

    private final CycleService cycleService;
    private final SpofService spofService;
    private final DependencyDriftRepository driftRepository;
    private final GraphNodeRepository graphNodeRepository;

    public RiskService(CycleService cycleService, SpofService spofService,
            DependencyDriftRepository driftRepository, GraphNodeRepository graphNodeRepository) {
        this.cycleService = cycleService;
        this.spofService = spofService;
        this.driftRepository = driftRepository;
        this.graphNodeRepository = graphNodeRepository;
    }

    public PageResult<Risk> list(UUID tenantId, int limit, int offset) {
        List<Risk> risks = new ArrayList<>();
        risks.addAll(cycleRisks(tenantId));
        risks.addAll(spofRisks(tenantId));
        risks.addAll(orphanRisks(tenantId));
        risks.addAll(driftRisks(tenantId));

        risks.sort(Comparator.comparing(Risk::severity)
                .thenComparing(Risk::type)
                .thenComparing(Risk::title));

        int total = risks.size();
        List<Risk> page = risks.stream().skip(offset).limit(limit).toList();
        return PageResult.of(page, total, limit, offset);
    }

    private List<Risk> cycleRisks(UUID tenantId) {
        Cycles cycles = cycleService.find(tenantId, null);
        if (cycles.cycles().isEmpty()) {
            return List.of();
        }
        Set<UUID> memberIds = cycles.cycles().stream()
                .flatMap(cycle -> cycle.members().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, GraphNode> nodesById = nodesById(tenantId, memberIds);

        return cycles.cycles().stream()
                .map(cycle -> {
                    List<UUID> members = cycle.members();
                    String id = "cycle:" + members.stream().map(UUID::toString).collect(Collectors.joining(","));
                    RiskSeverity severity = cycleSeverity(members, nodesById);
                    String title = "Circular dependency among %d services".formatted(members.size());
                    String explanation = "These services form a circular dependency chain: each depends, "
                            + "directly or transitively, on itself.";
                    String fix = "Break the cycle by removing or inverting one of the dependencies in the chain.";
                    return new Risk(id, RiskType.CYCLE, severity, title, explanation, fix, members);
                })
                .toList();
    }

    private static RiskSeverity cycleSeverity(List<UUID> members, Map<UUID, GraphNode> nodesById) {
        boolean anyCritical = members.stream().anyMatch(id -> isTier(nodesById.get(id), "CRITICAL"));
        if (anyCritical) {
            return RiskSeverity.CRITICAL;
        }
        boolean allExperimental = members.stream().allMatch(id -> isTier(nodesById.get(id), "EXPERIMENTAL"));
        return allExperimental ? RiskSeverity.INFO : RiskSeverity.WARNING;
    }

    private static boolean isTier(GraphNode node, String tier) {
        return node != null && tier.equals(node.tier());
    }

    private List<Risk> spofRisks(UUID tenantId) {
        SpofResult result = spofService.detect(tenantId);
        return result.items().stream()
                .map(spof -> {
                    GraphNode node = spof.node();
                    String id = "spof:" + node.serviceId();
                    String title = "Single point of failure: %s".formatted(node.name());
                    String explanation = "%s has %d direct dependents, at or above the tenant's threshold of %d."
                            .formatted(node.name(), spof.fanIn(), result.threshold());
                    String fix = "Add redundancy or split responsibilities for this service.";
                    return new Risk(id, RiskType.SPOF, spof.severity(), title, explanation, fix,
                            List.of(node.serviceId()));
                })
                .toList();
    }

    private List<Risk> orphanRisks(UUID tenantId) {
        return graphNodeRepository.findOrphaned(tenantId, SpofService.MAX_ROWS).stream()
                .map(node -> {
                    String id = "orphan:" + node.serviceId();
                    RiskSeverity severity = orphanSeverity(node);
                    String title = "Unowned service: %s".formatted(node.name());
                    String explanation = "%s has no owning team assigned.".formatted(node.name());
                    String fix = "Assign an owning team in the Service Catalog.";
                    return new Risk(id, RiskType.ORPHAN, severity, title, explanation, fix,
                            List.of(node.serviceId()));
                })
                .toList();
    }

    private static RiskSeverity orphanSeverity(GraphNode node) {
        if ("CRITICAL".equals(node.tier())) {
            return RiskSeverity.CRITICAL;
        }
        if ("EXPERIMENTAL".equals(node.tier())) {
            return RiskSeverity.INFO;
        }
        return RiskSeverity.WARNING;
    }

    private List<Risk> driftRisks(UUID tenantId) {
        List<DependencyDrift> drifts = driftRepository.findActive(tenantId, SpofService.MAX_ROWS, 0);
        if (drifts.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = drifts.stream()
                .flatMap(drift -> Stream.of(drift.sourceServiceId(), drift.targetServiceId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, GraphNode> nodesById = nodesById(tenantId, ids);

        return drifts.stream()
                .map(drift -> {
                    String source = nameOf(nodesById, drift.sourceServiceId());
                    String target = nameOf(nodesById, drift.targetServiceId());
                    String id = "drift:" + drift.id();
                    List<UUID> affected = List.of(drift.sourceServiceId(), drift.targetServiceId());
                    return switch (drift.type()) {
                        case UNDECLARED -> new Risk(id, RiskType.DRIFT, RiskSeverity.WARNING,
                                "Undeclared dependency: %s → %s".formatted(source, target),
                                "An active dependency from %s to %s was observed but has not been declared."
                                        .formatted(source, target),
                                "Declare this dependency, or investigate whether the traffic is unexpected.",
                                affected);
                        case MISSING -> new Risk(id, RiskType.DRIFT, RiskSeverity.INFO,
                                "Missing dependency: %s → %s".formatted(source, target),
                                "A declared dependency from %s to %s has not been observed recently."
                                        .formatted(source, target),
                                "Confirm whether this dependency is still in use, or remove the declaration.",
                                affected);
                    };
                })
                .toList();
    }

    private static String nameOf(Map<UUID, GraphNode> nodesById, UUID serviceId) {
        GraphNode node = nodesById.get(serviceId);
        return node != null ? node.name() : serviceId.toString();
    }

    private Map<UUID, GraphNode> nodesById(UUID tenantId, Set<UUID> ids) {
        return graphNodeRepository.findByServiceIds(tenantId, ids, ids.size()).stream()
                .collect(Collectors.toMap(GraphNode::serviceId, Function.identity()));
    }
}
