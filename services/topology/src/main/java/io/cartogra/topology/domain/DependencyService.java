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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD on declared dependencies — POST/PUT/DELETE {@code /dependencies}. Every mutation
 * validates against {@code graph_nodes}, enforces "either side" team-membership authorization
 * (via Registry's internal membership-check endpoint, fail closed), and marks the
 * {@code dependency_graph_edges} materialized view dirty for the next scheduled refresh.
 *
 * <p>Authorization is ADMIN or team membership — plain membership, not the {@code TEAM_OWNER}
 * role tier. {@code team_members} rows carry no rank of their own, so any member of the team
 * owning the source or target service may manage the edge; there is no coarse role-based gate
 * on top of that (see {@link #requireEitherSideAccess}).
 *
 * <p>This service only ever touches rows with {@code type = DECLARED}: {@link #update} and
 * {@link #delete} treat an id belonging to an {@code observed} edge as not found, since those
 * are owned exclusively by the observed-edge ingestion flow.
 */
@org.springframework.stereotype.Service
public class DependencyService {

    private final DependencyRepository dependencyRepository;
    private final GraphNodeRepository graphNodeRepository;
    private final RegistryMembershipClient registryMembershipClient;
    private final DependencyGraphViewRefreshScheduler graphViewRefreshScheduler;

    public DependencyService(DependencyRepository dependencyRepository,
            GraphNodeRepository graphNodeRepository,
            RegistryMembershipClient registryMembershipClient,
            DependencyGraphViewRefreshScheduler graphViewRefreshScheduler) {
        this.dependencyRepository = dependencyRepository;
        this.graphNodeRepository = graphNodeRepository;
        this.registryMembershipClient = registryMembershipClient;
        this.graphViewRefreshScheduler = graphViewRefreshScheduler;
    }

    @Transactional
    public Dependency create(UUID tenantId, @Nullable UUID userId, DeclareDependencyRequest request) {
        requireEitherSideAccess(tenantId, userId, request.sourceServiceId(), request.targetServiceId());
        validateNodes(tenantId, request.sourceServiceId(), request.targetServiceId());
        requireNotSelfEdge(request.sourceServiceId(), request.targetServiceId());
        requireNoDuplicate(tenantId, request.sourceServiceId(), request.targetServiceId(), request.protocol(), null);

        Instant now = Instant.now();
        Dependency saved = dependencyRepository.save(new Dependency(
                UUID.randomUUID(), tenantId, request.sourceServiceId(), request.targetServiceId(),
                DependencyType.DECLARED, request.protocol(), request.metadata(), now, now, null));
        graphViewRefreshScheduler.markDirty();
        return saved;
    }

    @Transactional
    public Dependency update(UUID tenantId, @Nullable UUID userId, UUID id, DeclareDependencyRequest request) {
        Dependency existing = loadDeclared(tenantId, id);

        // Authorization before any check that reveals server state (same reasoning as
        // create()). Caller must be authorized for BOTH the pre-edit pair (to be allowed to
        // touch this edge at all) and the post-edit pair (since that's what's being asserted)
        // — one Registry call covering up to 4 distinct serviceIds, evaluated as two pairs.
        if (!isAdmin()) {
            Map<UUID, Boolean> access = fetchAccess(tenantId, userId,
                    existing.sourceServiceId(), existing.targetServiceId(),
                    request.sourceServiceId(), request.targetServiceId());
            requirePairAuthorized(access, existing.sourceServiceId(), existing.targetServiceId());
            requirePairAuthorized(access, request.sourceServiceId(), request.targetServiceId());
        }

        validateNodes(tenantId, request.sourceServiceId(), request.targetServiceId());
        requireNotSelfEdge(request.sourceServiceId(), request.targetServiceId());
        requireNoDuplicate(tenantId, request.sourceServiceId(), request.targetServiceId(), request.protocol(), id);

        Dependency saved = dependencyRepository.save(new Dependency(
                existing.id(), tenantId, request.sourceServiceId(), request.targetServiceId(),
                DependencyType.DECLARED, request.protocol(), request.metadata(),
                existing.createdAt(), Instant.now(), null));
        graphViewRefreshScheduler.markDirty();
        return saved;
    }

    @Transactional
    public void delete(UUID tenantId, @Nullable UUID userId, UUID id) {
        Dependency existing = loadDeclared(tenantId, id);
        requireEitherSideAccess(tenantId, userId, existing.sourceServiceId(), existing.targetServiceId());

        dependencyRepository.softDelete(tenantId, id);
        graphViewRefreshScheduler.markDirty();
    }

    private Dependency loadDeclared(UUID tenantId, UUID id) {
        Dependency existing = dependencyRepository.findById(tenantId, id)
                .orElseThrow(() -> new DependencyNotFoundException(id));
        if (existing.type() != DependencyType.DECLARED) {
            throw new DependencyNotFoundException(id);
        }
        return existing;
    }

    private void validateNodes(UUID tenantId, UUID sourceServiceId, UUID targetServiceId) {
        requireLiveNode(tenantId, sourceServiceId);
        requireLiveNode(tenantId, targetServiceId);
    }

    private void requireLiveNode(UUID tenantId, UUID serviceId) {
        GraphNode node = graphNodeRepository.findByServiceId(tenantId, serviceId)
                .orElseThrow(() -> new UnknownServiceNodeException(serviceId));
        if (node.isDeleted()) {
            throw new UnknownServiceNodeException(serviceId);
        }
    }

    private void requireNotSelfEdge(UUID sourceServiceId, UUID targetServiceId) {
        if (sourceServiceId.equals(targetServiceId)) {
            throw new SelfDependencyException(sourceServiceId);
        }
    }

    private void requireNoDuplicate(UUID tenantId, UUID sourceServiceId, UUID targetServiceId,
            DependencyProtocol protocol, @Nullable UUID excludingDependencyId) {
        dependencyRepository.findByEdgeIdentity(tenantId, sourceServiceId, targetServiceId,
                        DependencyType.DECLARED, protocol)
                .filter(match -> !match.id().equals(excludingDependencyId))
                .ifPresent(match -> {
                    throw new DuplicateDependencyException(sourceServiceId, targetServiceId);
                });
    }

    /** ADMIN bypasses entirely — never calls Registry. Otherwise "either side" via Registry. */
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
