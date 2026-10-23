package io.cartogra.topology.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.topology.domain.exception.DriftAlreadyResolvedException;
import io.cartogra.topology.domain.exception.DriftNotFoundException;
import io.cartogra.topology.domain.exception.InvalidDriftActionException;
import io.cartogra.topology.infrastructure.scheduled.DependencyGraphViewRefreshScheduler;
import io.cartogra.topology.repository.DependencyDriftRepository;
import io.cartogra.topology.repository.DependencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriftServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Mock DependencyDriftRepository driftRepository;
    @Mock DependencyRepository dependencyRepository;
    @Mock DependencyService dependencyService;
    @Mock DependencyGraphViewRefreshScheduler refreshScheduler;
    @Mock PlatformTransactionManager transactionManager;

    private DriftService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new DriftService(driftRepository, dependencyRepository, dependencyService, refreshScheduler,
                transactionManager);
    }

    private DependencyDrift drift(DriftType type, boolean resolved) {
        return new DependencyDrift(UUID.randomUUID(), TENANT, A, B, type, Instant.now(),
                resolved ? Instant.now() : null, null, resolved ? DriftResolution.DISMISSED : null);
    }

    private DependencyDrift stub(DependencyDrift drift) {
        when(driftRepository.findById(TENANT, drift.id())).thenReturn(Optional.of(drift));
        return drift;
    }

    @Test
    void acceptObservedDeclaresEveryObservedProtocolNotAlreadyDeclared() {
        DependencyDrift drift = stub(drift(DriftType.UNDECLARED, false));
        when(driftRepository.resolve(eq(TENANT), eq(drift.id()), eq(USER), eq(DriftResolution.ACCEPTED), any()))
                .thenReturn(true);
        when(dependencyRepository.findLiveProtocols(TENANT, A, B, DependencyType.OBSERVED))
                .thenReturn(List.of(DependencyProtocol.HTTP, DependencyProtocol.GRPC));
        when(dependencyRepository.findByEdgeIdentity(TENANT, A, B, DependencyType.DECLARED, DependencyProtocol.HTTP))
                .thenReturn(Optional.empty());
        when(dependencyRepository.findByEdgeIdentity(TENANT, A, B, DependencyType.DECLARED, DependencyProtocol.GRPC))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(Dependency.class)));

        service.resolve(TENANT, USER, drift.id(), DriftAction.ACCEPT_OBSERVED);

        ArgumentCaptor<Dependency> saved = ArgumentCaptor.forClass(Dependency.class);
        verify(dependencyRepository).save(saved.capture());
        assertThat(saved.getValue().type()).isEqualTo(DependencyType.DECLARED);
        assertThat(saved.getValue().protocol()).isEqualTo(DependencyProtocol.HTTP);
        verify(refreshScheduler).markDirty();
    }

    @Test
    void removeDeclaredSoftDeletesThePair() {
        DependencyDrift drift = stub(drift(DriftType.MISSING, false));
        when(driftRepository.resolve(eq(TENANT), eq(drift.id()), eq(USER), eq(DriftResolution.REMOVED), any()))
                .thenReturn(true);

        service.resolve(TENANT, USER, drift.id(), DriftAction.REMOVE_DECLARED);

        verify(dependencyRepository).softDeleteDeclaredPair(TENANT, A, B);
        verify(refreshScheduler).markDirty();
    }

    @Test
    void dismissOnlyClosesTheRecord() {
        DependencyDrift drift = stub(drift(DriftType.MISSING, false));
        when(driftRepository.resolve(eq(TENANT), eq(drift.id()), eq(USER), eq(DriftResolution.DISMISSED), any()))
                .thenReturn(true);

        service.resolve(TENANT, USER, drift.id(), DriftAction.DISMISS);

        verify(dependencyRepository, never()).save(any());
        verify(dependencyRepository, never()).softDeleteDeclaredPair(any(), any(), any());
        verify(refreshScheduler, never()).markDirty();
    }

    @Test
    void wrongActionForTypeIsRejected() {
        DependencyDrift undeclared = stub(drift(DriftType.UNDECLARED, false));
        DependencyDrift missing = stub(drift(DriftType.MISSING, false));

        assertThatThrownBy(() -> service.resolve(TENANT, USER, undeclared.id(), DriftAction.REMOVE_DECLARED))
                .isInstanceOf(InvalidDriftActionException.class);
        assertThatThrownBy(() -> service.resolve(TENANT, USER, missing.id(), DriftAction.ACCEPT_OBSERVED))
                .isInstanceOf(InvalidDriftActionException.class);
        verify(driftRepository, never()).resolve(any(), any(), any(), any(), any());
    }

    @Test
    void alreadyResolvedDriftIsRejected() {
        DependencyDrift drift = stub(drift(DriftType.MISSING, true));

        assertThatThrownBy(() -> service.resolve(TENANT, USER, drift.id(), DriftAction.DISMISS))
                .isInstanceOf(DriftAlreadyResolvedException.class);
    }

    @Test
    void lostRaceOnResolveIsReportedAsAlreadyResolved() {
        DependencyDrift drift = stub(drift(DriftType.MISSING, false));
        when(driftRepository.resolve(eq(TENANT), eq(drift.id()), eq(USER), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.resolve(TENANT, USER, drift.id(), DriftAction.REMOVE_DECLARED))
                .isInstanceOf(DriftAlreadyResolvedException.class);
        verify(dependencyRepository, never()).softDeleteDeclaredPair(any(), any(), any());
    }

    @Test
    void unknownDriftIsNotFound() {
        UUID id = UUID.randomUUID();
        when(driftRepository.findById(TENANT, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(TENANT, USER, id, DriftAction.DISMISS))
                .isInstanceOf(DriftNotFoundException.class);
    }

    @Test
    void unauthorizedCallerChangesNothing() {
        DependencyDrift drift = stub(drift(DriftType.MISSING, false));
        doThrow(new AccessDeniedException("no")).when(dependencyService)
                .requireEitherSideAccess(TENANT, USER, A, B);

        assertThatThrownBy(() -> service.resolve(TENANT, USER, drift.id(), DriftAction.DISMISS))
                .isInstanceOf(AccessDeniedException.class);
        verify(driftRepository, never()).resolve(any(), any(), any(), any(), any());
    }

    @Test
    void listClampsPaging() {
        when(driftRepository.findActive(TENANT, 200, 0)).thenReturn(List.of());
        when(driftRepository.countActive(TENANT)).thenReturn(0L);

        PageResult<DependencyDrift> page = service.list(TENANT, 9999, -5);

        assertThat(page.limit()).isEqualTo(200);
        assertThat(page.offset()).isZero();
    }
}
