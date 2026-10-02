package io.cartogra.topology.domain;

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

    public static final int MAX_PAGE_SIZE = 1000;

    private static final int SOURCE_CAP = SpofService.MAX_ROWS;

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

    private record Found(List<Risk> risks, boolean truncated) {
        static Found of(List<Risk> risks) {
            return new Found(risks, false);
        }
    }

    /**
     * {@code total} counts only what each source returned under its cap; {@code truncated} is true
     * when any source hit its cap, so the true total may be higher.
     */
    public RiskPage list(UUID tenantId, int limit, int offset) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        List<Found> sources = List.of(cycleRisks(tenantId), spofRisks(tenantId),
                orphanRisks(tenantId), driftRisks(tenantId));
        List<Risk> risks = new ArrayList<>();
        sources.forEach(source -> risks.addAll(source.risks()));
        boolean truncated = sources.stream().anyMatch(Found::truncated);

        risks.sort(Comparator.comparing(Risk::severity)
                .thenComparing(Risk::type)
                .thenComparing(Risk::title)
                .thenComparing(Risk::id));

        int total = risks.size();
        List<Risk> page = risks.stream().skip(offset).limit(limit).toList();
        return new RiskPage(page, total, limit, offset, truncated);
    }

    private Found cycleRisks(UUID tenantId) {
        Cycles cycles = cycleService.find(tenantId, null);
        if (cycles.cycles().isEmpty()) {
            return new Found(List.of(), cycles.truncated());
        }
        Set<UUID> memberIds = cycles.cycles().stream()
                .flatMap(cycle -> cycle.members().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, GraphNode> nodesById = nodesById(tenantId, memberIds);

        List<Risk> risks = cycles.cycles().stream()
                .filter(cycle -> cycle.members().stream().allMatch(nodesById::containsKey))
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
        return new Found(risks, cycles.truncated());
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

    private Found spofRisks(UUID tenantId) {
        SpofResult result = spofService.detect(tenantId);
        List<Risk> risks = result.items().stream()
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
        return new Found(risks, result.truncated());
    }

    private Found orphanRisks(UUID tenantId) {
        List<GraphNode> fetched = graphNodeRepository.findOrphaned(tenantId, SOURCE_CAP + 1);
        boolean truncated = fetched.size() > SOURCE_CAP;
        List<Risk> risks = fetched.stream().limit(SOURCE_CAP)
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
        return new Found(risks, truncated);
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

    private Found driftRisks(UUID tenantId) {
        List<DependencyDrift> fetched = driftRepository.findActive(tenantId, SOURCE_CAP + 1, 0);
        boolean truncated = fetched.size() > SOURCE_CAP;
        List<DependencyDrift> drifts = truncated ? fetched.subList(0, SOURCE_CAP) : fetched;
        if (drifts.isEmpty()) {
            return Found.of(List.of());
        }
        Set<UUID> ids = drifts.stream()
                .flatMap(drift -> Stream.of(drift.sourceServiceId(), drift.targetServiceId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, GraphNode> nodesById = nodesById(tenantId, ids);

        List<Risk> risks = drifts.stream()
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
        return new Found(risks, truncated);
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
