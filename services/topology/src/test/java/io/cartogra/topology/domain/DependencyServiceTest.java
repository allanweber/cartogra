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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DependencyServiceTest {

    @Mock DependencyRepository dependencyRepository;
    @Mock GraphNodeRepository graphNodeRepository;
    @Mock RegistryMembershipClient registryMembershipClient;
    @Mock DependencyGraphViewRefreshScheduler graphViewRefreshScheduler;

    private DependencyService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DependencyService(dependencyRepository, graphNodeRepository,
                registryMembershipClient, graphViewRefreshScheduler);
        // Plain non-admin authority by default — exercises the Registry membership-check
        // path. Deliberately NOT ROLE_TEAM_OWNER: role tier is irrelevant, only ADMIN or
        // actual team membership matters. ADMIN-specific tests override this.
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", null, new SimpleGrantedAuthority("ROLE_MEMBER")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private GraphNode liveNode(UUID serviceId) {
        return new GraphNode(UUID.randomUUID(), tenantId, serviceId, "svc", null, null, "HEALTHY",
                Instant.now(), Instant.now(), null);
    }

    private GraphNode deletedNode(UUID serviceId) {
        return new GraphNode(UUID.randomUUID(), tenantId, serviceId, "svc", null, null, "HEALTHY",
                Instant.now(), Instant.now(), Instant.now());
    }

    private void stubLiveNodes() {
        when(graphNodeRepository.findByServiceId(tenantId, sourceId)).thenReturn(Optional.of(liveNode(sourceId)));
        when(graphNodeRepository.findByServiceId(tenantId, targetId)).thenReturn(Optional.of(liveNode(targetId)));
    }

    private void stubNoDuplicate() {
        when(dependencyRepository.findByEdgeIdentity(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
    }

    private void stubFullAccess() {
        when(registryMembershipClient.checkAccess(eq(tenantId), eq(userId), anyList()))
                .thenReturn(Map.of(sourceId, true, targetId, true));
    }

    private DeclareDependencyRequest request(UUID source, UUID target) {
        return new DeclareDependencyRequest(source, target, DependencyProtocol.HTTP, null);
    }

    private Dependency existingDependency(UUID id, UUID source, UUID target, DependencyType type) {
        return new Dependency(id, tenantId, source, target, type, DependencyProtocol.HTTP, null,
                Instant.now(), Instant.now(), null);
    }

    // ---- create ----

    @Test
    void create_success_savesWithDeclaredTypeAndMarksDirty() {
        stubLiveNodes();
        stubNoDuplicate();
        stubFullAccess();
        when(dependencyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Dependency result = service.create(tenantId, userId, request(sourceId, targetId));

        assertThat(result.type()).isEqualTo(DependencyType.DECLARED);
        assertThat(result.sourceServiceId()).isEqualTo(sourceId);
        assertThat(result.targetServiceId()).isEqualTo(targetId);
        verify(graphViewRefreshScheduler).markDirty();
    }

    @Test
    void create_unknownSourceNode_throwsUnknownServiceNodeException() {
        when(graphNodeRepository.findByServiceId(tenantId, sourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(tenantId, userId, request(sourceId, targetId)))
                .isInstanceOf(UnknownServiceNodeException.class);
        verify(dependencyRepository, never()).save(any());
        verify(graphViewRefreshScheduler, never()).markDirty();
    }

    @Test
    void create_unknownTargetNode_throwsUnknownServiceNodeException() {
        when(graphNodeRepository.findByServiceId(tenantId, sourceId)).thenReturn(Optional.of(liveNode(sourceId)));
        when(graphNodeRepository.findByServiceId(tenantId, targetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(tenantId, userId, request(sourceId, targetId)))
                .isInstanceOf(UnknownServiceNodeException.class);
        verify(graphViewRefreshScheduler, never()).markDirty();
    }

    @Test
    void create_softDeletedSourceNode_throwsUnknownServiceNodeException() {
        when(graphNodeRepository.findByServiceId(tenantId, sourceId)).thenReturn(Optional.of(deletedNode(sourceId)));

        assertThatThrownBy(() -> service.create(tenantId, userId, request(sourceId, targetId)))
                .isInstanceOf(UnknownServiceNodeException.class);
    }

    @Test
    void create_softDeletedTargetNode_throwsUnknownServiceNodeException() {
        when(graphNodeRepository.findByServiceId(tenantId, sourceId)).thenReturn(Optional.of(liveNode(sourceId)));
        when(graphNodeRepository.findByServiceId(tenantId, targetId)).thenReturn(Optional.of(deletedNode(targetId)));

        assertThatThrownBy(() -> service.create(tenantId, userId, request(sourceId, targetId)))
                .isInstanceOf(UnknownServiceNodeException.class);
    }

    @Test
    void create_selfEdge_throwsSelfDependencyException() {
        when(graphNodeRepository.findByServiceId(tenantId, sourceId)).thenReturn(Optional.of(liveNode(sourceId)));

        assertThatThrownBy(() -> service.create(tenantId, userId, request(sourceId, sourceId)))
                .isInstanceOf(SelfDependencyException.class);
        verify(graphViewRefreshScheduler, never()).markDirty();
    }

    @Test
    void create_duplicateEdgeSameProtocol_throwsDuplicateDependencyException() {
        stubLiveNodes();
        Dependency existing = existingDependency(UUID.randomUUID(), sourceId, targetId, DependencyType.DECLARED);
        when(dependencyRepository.findByEdgeIdentity(tenantId, sourceId, targetId, DependencyType.DECLARED, DependencyProtocol.HTTP))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(tenantId, userId, request(sourceId, targetId)))
                .isInstanceOf(DuplicateDependencyException.class);
        verify(graphViewRefreshScheduler, never()).markDirty();
    }

    @Test
    void create_sameServicePairDifferentProtocol_succeeds() {
        stubLiveNodes();
        stubFullAccess();
        // findByEdgeIdentity is queried with the HTTP protocol specifically — a GRPC edge
        // between the same pair doesn't match, so it returns empty for this exact identity.
        when(dependencyRepository.findByEdgeIdentity(tenantId, sourceId, targetId, DependencyType.DECLARED, DependencyProtocol.HTTP))
                .thenReturn(Optional.empty());
        when(dependencyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Dependency result = service.create(tenantId, userId, request(sourceId, targetId));

        assertThat(result.protocol()).isEqualTo(DependencyProtocol.HTTP);
    }

    @Test
    void create_adminCaller_skipsRegistryCallEntirely() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin", null, new SimpleGrantedAuthority("ROLE_ADMIN")));
        stubLiveNodes();
        stubNoDuplicate();
        when(dependencyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.create(tenantId, userId, request(sourceId, targetId));

        verifyNoInteractions(registryMembershipClient);
    }

    @Test
    void create_teamOwnerMemberOfSourceOnly_succeeds() {
        stubLiveNodes();
        stubNoDuplicate();
        when(registryMembershipClient.checkAccess(eq(tenantId), eq(userId), anyList()))
                .thenReturn(Map.of(sourceId, true, targetId, false));
        when(dependencyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create(tenantId, userId, request(sourceId, targetId))).isNotNull();
    }

    @Test
    void create_teamOwnerMemberOfTargetOnly_succeeds() {
        stubLiveNodes();
        stubNoDuplicate();
        when(registryMembershipClient.checkAccess(eq(tenantId), eq(userId), anyList()))
                .thenReturn(Map.of(sourceId, false, targetId, true));
        when(dependencyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create(tenantId, userId, request(sourceId, targetId))).isNotNull();
    }

    @Test
    void create_teamOwnerMemberOfNeither_throwsAccessDenied() {
        stubLiveNodes();
        stubNoDuplicate();
        when(registryMembershipClient.checkAccess(eq(tenantId), eq(userId), anyList()))
                .thenReturn(Map.of(sourceId, false, targetId, false));

        assertThatThrownBy(() -> service.create(tenantId, userId, request(sourceId, targetId)))
                .isInstanceOf(AccessDeniedException.class);
        verify(dependencyRepository, never()).save(any());
        verify(graphViewRefreshScheduler, never()).markDirty();
    }

    // ---- update ----

    @Test
    void update_fullReplace_success() {
        UUID id = UUID.randomUUID();
        UUID newTarget = UUID.randomUUID();
        Dependency existing = existingDependency(id, sourceId, targetId, DependencyType.DECLARED);
        when(dependencyRepository.findById(tenantId, id)).thenReturn(Optional.of(existing));
        when(graphNodeRepository.findByServiceId(tenantId, sourceId)).thenReturn(Optional.of(liveNode(sourceId)));
        when(graphNodeRepository.findByServiceId(tenantId, newTarget)).thenReturn(Optional.of(liveNode(newTarget)));
        stubNoDuplicate();
        when(registryMembershipClient.checkAccess(eq(tenantId), eq(userId), anyList()))
                .thenReturn(Map.of(sourceId, true, targetId, true, newTarget, true));
        when(dependencyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Dependency result = service.update(tenantId, userId, id, request(sourceId, newTarget));

        assertThat(result.targetServiceId()).isEqualTo(newTarget);
        assertThat(result.id()).isEqualTo(id);
        verify(graphViewRefreshScheduler).markDirty();
    }

    @Test
    void update_idNotFound_throwsDependencyNotFoundException() {
        UUID id = UUID.randomUUID();
        when(dependencyRepository.findById(tenantId, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(tenantId, userId, id, request(sourceId, targetId)))
                .isInstanceOf(DependencyNotFoundException.class);
    }

    @Test
    void update_idIsObservedType_throwsDependencyNotFoundException() {
        UUID id = UUID.randomUUID();
        Dependency observed = existingDependency(id, sourceId, targetId, DependencyType.OBSERVED);
        when(dependencyRepository.findById(tenantId, id)).thenReturn(Optional.of(observed));

        assertThatThrownBy(() -> service.update(tenantId, userId, id, request(sourceId, targetId)))
                .isInstanceOf(DependencyNotFoundException.class);
    }

    @Test
    void update_authorizedForNewPairOnly_throwsAccessDenied() {
        UUID id = UUID.randomUUID();
        UUID newSource = UUID.randomUUID();
        UUID newTarget = UUID.randomUUID();
        Dependency existing = existingDependency(id, sourceId, targetId, DependencyType.DECLARED);
        when(dependencyRepository.findById(tenantId, id)).thenReturn(Optional.of(existing));
        when(graphNodeRepository.findByServiceId(tenantId, newSource)).thenReturn(Optional.of(liveNode(newSource)));
        when(graphNodeRepository.findByServiceId(tenantId, newTarget)).thenReturn(Optional.of(liveNode(newTarget)));
        stubNoDuplicate();
        // New pair (newSource, newTarget) is authorized; old pair (sourceId, targetId) is not.
        when(registryMembershipClient.checkAccess(eq(tenantId), eq(userId), anyList()))
                .thenReturn(Map.of(sourceId, false, targetId, false, newSource, true, newTarget, true));

        assertThatThrownBy(() -> service.update(tenantId, userId, id, request(newSource, newTarget)))
                .isInstanceOf(AccessDeniedException.class);
        verify(dependencyRepository, never()).save(any());
    }

    @Test
    void update_authorizedForOldPairOnly_throwsAccessDenied() {
        UUID id = UUID.randomUUID();
        UUID newSource = UUID.randomUUID();
        UUID newTarget = UUID.randomUUID();
        Dependency existing = existingDependency(id, sourceId, targetId, DependencyType.DECLARED);
        when(dependencyRepository.findById(tenantId, id)).thenReturn(Optional.of(existing));
        when(graphNodeRepository.findByServiceId(tenantId, newSource)).thenReturn(Optional.of(liveNode(newSource)));
        when(graphNodeRepository.findByServiceId(tenantId, newTarget)).thenReturn(Optional.of(liveNode(newTarget)));
        stubNoDuplicate();
        // Old pair (sourceId, targetId) is authorized; new pair (newSource, newTarget) is not.
        when(registryMembershipClient.checkAccess(eq(tenantId), eq(userId), anyList()))
                .thenReturn(Map.of(sourceId, true, targetId, true, newSource, false, newTarget, false));

        assertThatThrownBy(() -> service.update(tenantId, userId, id, request(newSource, newTarget)))
                .isInstanceOf(AccessDeniedException.class);
        verify(dependencyRepository, never()).save(any());
    }

    @Test
    void update_newValuesDuplicateAnotherRow_throwsDuplicateDependencyException() {
        UUID id = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        UUID newTarget = UUID.randomUUID();
        Dependency existing = existingDependency(id, sourceId, targetId, DependencyType.DECLARED);
        Dependency collidingRow = existingDependency(otherId, sourceId, newTarget, DependencyType.DECLARED);
        when(dependencyRepository.findById(tenantId, id)).thenReturn(Optional.of(existing));
        when(graphNodeRepository.findByServiceId(tenantId, sourceId)).thenReturn(Optional.of(liveNode(sourceId)));
        when(graphNodeRepository.findByServiceId(tenantId, newTarget)).thenReturn(Optional.of(liveNode(newTarget)));
        when(dependencyRepository.findByEdgeIdentity(tenantId, sourceId, newTarget, DependencyType.DECLARED, DependencyProtocol.HTTP))
                .thenReturn(Optional.of(collidingRow));

        assertThatThrownBy(() -> service.update(tenantId, userId, id, request(sourceId, newTarget)))
                .isInstanceOf(DuplicateDependencyException.class);
    }

    @Test
    void update_newValuesMatchOwnRow_succeeds() {
        UUID id = UUID.randomUUID();
        Dependency existing = existingDependency(id, sourceId, targetId, DependencyType.DECLARED);
        stubLiveNodes();
        // findByEdgeIdentity matches the row being updated itself — must be excluded, not treated as a dup.
        when(dependencyRepository.findById(tenantId, id)).thenReturn(Optional.of(existing));
        when(dependencyRepository.findByEdgeIdentity(tenantId, sourceId, targetId, DependencyType.DECLARED, DependencyProtocol.HTTP))
                .thenReturn(Optional.of(existing));
        stubFullAccess();
        when(dependencyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.update(tenantId, userId, id, request(sourceId, targetId))).isNotNull();
    }

    @Test
    void update_newSourceEqualsNewTarget_throwsSelfDependencyException() {
        UUID id = UUID.randomUUID();
        Dependency existing = existingDependency(id, sourceId, targetId, DependencyType.DECLARED);
        when(dependencyRepository.findById(tenantId, id)).thenReturn(Optional.of(existing));
        when(graphNodeRepository.findByServiceId(tenantId, sourceId)).thenReturn(Optional.of(liveNode(sourceId)));

        assertThatThrownBy(() -> service.update(tenantId, userId, id, request(sourceId, sourceId)))
                .isInstanceOf(SelfDependencyException.class);
    }

    // ---- delete ----

    @Test
    void delete_success_softDeletesAndMarksDirty() {
        UUID id = UUID.randomUUID();
        Dependency existing = existingDependency(id, sourceId, targetId, DependencyType.DECLARED);
        when(dependencyRepository.findById(tenantId, id)).thenReturn(Optional.of(existing));
        stubFullAccess();

        service.delete(tenantId, userId, id);

        verify(dependencyRepository).softDelete(tenantId, id);
        verify(graphViewRefreshScheduler).markDirty();
    }

    @Test
    void delete_idNotFound_throwsDependencyNotFoundException() {
        UUID id = UUID.randomUUID();
        when(dependencyRepository.findById(tenantId, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(tenantId, userId, id))
                .isInstanceOf(DependencyNotFoundException.class);
        verify(graphViewRefreshScheduler, never()).markDirty();
    }

    @Test
    void delete_idIsObservedType_throwsDependencyNotFoundException() {
        UUID id = UUID.randomUUID();
        Dependency observed = existingDependency(id, sourceId, targetId, DependencyType.OBSERVED);
        when(dependencyRepository.findById(tenantId, id)).thenReturn(Optional.of(observed));

        assertThatThrownBy(() -> service.delete(tenantId, userId, id))
                .isInstanceOf(DependencyNotFoundException.class);
    }

    @Test
    void delete_unauthorizedCaller_throwsAccessDenied() {
        UUID id = UUID.randomUUID();
        Dependency existing = existingDependency(id, sourceId, targetId, DependencyType.DECLARED);
        when(dependencyRepository.findById(tenantId, id)).thenReturn(Optional.of(existing));
        when(registryMembershipClient.checkAccess(eq(tenantId), eq(userId), anyList()))
                .thenReturn(Map.of(sourceId, false, targetId, false));

        assertThatThrownBy(() -> service.delete(tenantId, userId, id))
                .isInstanceOf(AccessDeniedException.class);
        verify(dependencyRepository, never()).softDelete(any(), any());
        verify(graphViewRefreshScheduler, never()).markDirty();
    }
}
