package io.cartogra.topology.domain;

import io.cartogra.topology.api.dto.DeclareDependencyRequest;
import io.cartogra.topology.domain.exception.DependencyNotFoundException;
import io.cartogra.topology.domain.exception.DuplicateDependencyException;
import io.cartogra.topology.domain.exception.SelfDependencyException;
import io.cartogra.topology.domain.exception.UnknownServiceNodeException;
import io.cartogra.topology.infrastructure.registry.RegistryMembershipClient;
import io.cartogra.topology.infrastructure.scheduled.DependencyGraphViewRefreshScheduler;
import io.cartogra.topology.repository.DependencyRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CRUD on declared dependencies. Authorization is ADMIN or team membership on either side of
 * the edge (plain membership, not the {@code TEAM_OWNER} role tier) via Registry's internal
 * membership-check endpoint, checked before any state-revealing validation to avoid leaking
 * node-existence or duplicate-edge information to unauthorized callers.
 *
 * <p>Only touches rows with {@code type = DECLARED}; {@link #update} and {@link #delete} treat
 * an {@code observed} edge's id as not found.
 */
@org.springframework.stereotype.Service
public class DependencyService {

    private final DependencyRepository dependencyRepository;
    private final GraphNodeRepository graphNodeRepository;
    private final RegistryMembershipClient registryMembershipClient;
    private final DependencyGraphViewRefreshScheduler graphViewRefreshScheduler;
    private final TransactionTemplate transactionTemplate;

    public DependencyService(DependencyRepository dependencyRepository,
            GraphNodeRepository graphNodeRepository,
            RegistryMembershipClient registryMembershipClient,
            DependencyGraphViewRefreshScheduler graphViewRefreshScheduler,
            PlatformTransactionManager transactionManager) {
        this.dependencyRepository = dependencyRepository;
        this.graphNodeRepository = graphNodeRepository;
        this.registryMembershipClient = registryMembershipClient;
        this.graphViewRefreshScheduler = graphViewRefreshScheduler;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Dependency create(UUID tenantId, @Nullable UUID userId, DeclareDependencyRequest request) {
        requireEitherSideAccess(tenantId, userId, request.sourceServiceId(), request.targetServiceId());
        return transactionTemplate.execute(status -> {
            NodePair nodes = validateNodes(tenantId, request.sourceServiceId(), request.targetServiceId());
            requireNotSelfEdge(request.sourceServiceId(), request.targetServiceId());
            requireNoDuplicate(tenantId, request.sourceServiceId(), request.targetServiceId(), request.protocol(), null,
                    nodes);

            Instant now = Instant.now();
            Dependency saved = dependencyRepository.save(new Dependency(
                    UUID.randomUUID(), tenantId, request.sourceServiceId(), request.targetServiceId(),
                    DependencyType.DECLARED, request.protocol(), request.metadata(), now, now, null));
            graphViewRefreshScheduler.markDirty();
            return saved;
        });
    }

    public Dependency update(UUID tenantId, @Nullable UUID userId, UUID id, DeclareDependencyRequest request) {
        Dependency existing = loadDeclared(tenantId, id);

        if (!isAdmin()) {
            Map<UUID, Boolean> access = fetchAccess(tenantId, userId,
                    existing.sourceServiceId(), existing.targetServiceId(),
                    request.sourceServiceId(), request.targetServiceId());
            requirePairAuthorized(access, existing.sourceServiceId(), existing.targetServiceId());
            requirePairAuthorized(access, request.sourceServiceId(), request.targetServiceId());
        }

        return transactionTemplate.execute(status -> {
            NodePair nodes = validateNodes(tenantId, request.sourceServiceId(), request.targetServiceId());
            requireNotSelfEdge(request.sourceServiceId(), request.targetServiceId());
            requireNoDuplicate(tenantId, request.sourceServiceId(), request.targetServiceId(), request.protocol(), id,
                    nodes);

            Dependency saved = dependencyRepository.save(new Dependency(
                    existing.id(), tenantId, request.sourceServiceId(), request.targetServiceId(),
                    DependencyType.DECLARED, request.protocol(), request.metadata(),
                    existing.createdAt(), Instant.now(), null));
            graphViewRefreshScheduler.markDirty();
            return saved;
        });
    }

    public void delete(UUID tenantId, @Nullable UUID userId, UUID id) {
        Dependency existing = loadDeclared(tenantId, id);
        requireEitherSideAccess(tenantId, userId, existing.sourceServiceId(), existing.targetServiceId());

        transactionTemplate.executeWithoutResult(status -> {
            dependencyRepository.softDelete(tenantId, id);
            graphViewRefreshScheduler.markDirty();
        });
    }

    public ServiceDependencies findForService(UUID tenantId, UUID serviceId) {
        requireLiveNode(tenantId, serviceId);

        List<Dependency> edges = dependencyRepository.findByService(tenantId, serviceId).stream()
                .filter(dependency -> dependency.type() == DependencyType.DECLARED)
                .toList();
        List<Dependency> downstreamEdges = edges.stream()
                .filter(dependency -> dependency.sourceServiceId().equals(serviceId))
                .toList();
        List<Dependency> upstreamEdges = edges.stream()
                .filter(dependency -> dependency.targetServiceId().equals(serviceId))
                .toList();

        Set<UUID> counterpartIds = new LinkedHashSet<>();
        downstreamEdges.forEach(dependency -> counterpartIds.add(dependency.targetServiceId()));
        upstreamEdges.forEach(dependency -> counterpartIds.add(dependency.sourceServiceId()));
        Map<UUID, GraphNode> nodesById = graphNodeRepository
                .findByServiceIds(tenantId, counterpartIds, counterpartIds.size())
                .stream()
                .collect(Collectors.toMap(GraphNode::serviceId, Function.identity()));

        List<DependencyEdge> upstream = upstreamEdges.stream()
                .map(dependency -> toEdge(dependency, nodesById.get(dependency.sourceServiceId())))
                .filter(Objects::nonNull)
                .toList();
        List<DependencyEdge> downstream = downstreamEdges.stream()
                .map(dependency -> toEdge(dependency, nodesById.get(dependency.targetServiceId())))
                .filter(Objects::nonNull)
                .toList();

        return new ServiceDependencies(upstream, downstream);
    }

    private static @Nullable DependencyEdge toEdge(Dependency dependency, @Nullable GraphNode counterpart) {
        if (counterpart == null) {
            return null;
        }
        return new DependencyEdge(dependency.id(), counterpart, dependency.protocol(), dependency.metadata(),
                dependency.createdAt(), dependency.updatedAt());
    }

    private Dependency loadDeclared(UUID tenantId, UUID id) {
        Dependency existing = dependencyRepository.findById(tenantId, id)
                .orElseThrow(() -> new DependencyNotFoundException(id));
        if (existing.type() != DependencyType.DECLARED) {
            throw new DependencyNotFoundException(id);
        }
        return existing;
    }

    private record NodePair(GraphNode source, GraphNode target) {}

    private NodePair validateNodes(UUID tenantId, UUID sourceServiceId, UUID targetServiceId) {
        GraphNode source = requireLiveNode(tenantId, sourceServiceId);
        GraphNode target = requireLiveNode(tenantId, targetServiceId);
        return new NodePair(source, target);
    }

    private GraphNode requireLiveNode(UUID tenantId, UUID serviceId) {
        GraphNode node = graphNodeRepository.findByServiceId(tenantId, serviceId)
                .orElseThrow(() -> new UnknownServiceNodeException(serviceId));
        if (node.isDeleted()) {
            throw new UnknownServiceNodeException(serviceId);
        }
        return node;
    }

    private void requireNotSelfEdge(UUID sourceServiceId, UUID targetServiceId) {
        if (sourceServiceId.equals(targetServiceId)) {
            throw new SelfDependencyException(sourceServiceId);
        }
    }

    private void requireNoDuplicate(UUID tenantId, UUID sourceServiceId, UUID targetServiceId,
            DependencyProtocol protocol, @Nullable UUID excludingDependencyId, NodePair nodes) {
        dependencyRepository.findByEdgeIdentity(tenantId, sourceServiceId, targetServiceId,
                        DependencyType.DECLARED, protocol)
                .filter(match -> !match.id().equals(excludingDependencyId))
                .ifPresent(match -> {
                    throw new DuplicateDependencyException(nodes.source().name(), nodes.target().name());
                });
    }

    private void requireEitherSideAccess(UUID tenantId, @Nullable UUID userId, UUID sourceServiceId, UUID targetServiceId) {
        if (isAdmin()) {
            return;
        }
        Map<UUID, Boolean> access = fetchAccess(tenantId, userId, sourceServiceId, targetServiceId);
        requirePairAuthorized(access, sourceServiceId, targetServiceId);
    }

    private Map<UUID, Boolean> fetchAccess(UUID tenantId, @Nullable UUID userId, UUID... serviceIds) {
        if (userId == null) {
            return Map.of();
        }
        Set<UUID> distinct = new LinkedHashSet<>(List.of(serviceIds));
        return registryMembershipClient.checkAccess(tenantId, userId, distinct.stream().toList());
    }

    private static void requirePairAuthorized(Map<UUID, Boolean> access, UUID sourceServiceId, UUID targetServiceId) {
        boolean authorized = Boolean.TRUE.equals(access.get(sourceServiceId))
                || Boolean.TRUE.equals(access.get(targetServiceId));
        if (!authorized) {
            throw new AccessDeniedException("Only ADMIN or a member of the owning team may manage this dependency");
        }
    }

    private static boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
