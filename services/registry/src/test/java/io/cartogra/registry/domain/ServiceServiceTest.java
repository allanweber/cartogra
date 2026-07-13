package io.cartogra.registry.domain;

import io.cartogra.registry.api.dto.CreateServiceRequest;
import io.cartogra.registry.api.dto.UpdateServiceRequest;
import io.cartogra.registry.domain.event.OwnershipResolvedPayload;
import io.cartogra.registry.domain.exception.DuplicateServiceNameException;
import io.cartogra.registry.domain.exception.ServiceNotFoundException;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import io.cartogra.registry.infrastructure.kafka.ServiceLifecycleEventProducer;
import io.cartogra.registry.infrastructure.validation.HealthEndpointValidator;
import io.cartogra.registry.repository.ServiceDiscoveryCommand;
import io.cartogra.registry.repository.ServiceHistoryRepository;
import io.cartogra.registry.repository.ServiceRepository;
import io.cartogra.registry.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceServiceTest {

    @Mock ServiceRepository serviceRepository;
    @Mock ServiceHistoryRepository historyRepository;
    @Mock TeamRepository teamRepository;
    @Mock ServiceLifecycleEventProducer eventProducer;
    @Mock HealthEndpointValidator healthEndpointValidator;
    @Mock PlanLimitService planLimitService;

    private ServiceService serviceService;

    @BeforeEach
    void setUp() {
        lenient().when(planLimitService.getLimits(any()))
                .thenReturn(new PlanLimits(PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED, PlanLimits.UNLIMITED));
        serviceService = new ServiceService(
                serviceRepository, historyRepository, teamRepository,
                new ObjectMapper(), eventProducer, healthEndpointValidator, planLimitService);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin", null, new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void createSavesAndPublishesRegisteredEvent() {
        UUID tenantId = UUID.randomUUID();
        var req = new CreateServiceRequest("payments", null, null, null, null, null, null);
        Service saved = service(tenantId, "payments");
        when(serviceRepository.existsByName(tenantId, "payments", null)).thenReturn(false);
        when(serviceRepository.save(any())).thenReturn(saved);

        serviceService.create(tenantId, req, null);

        verify(serviceRepository).save(any());
        verify(historyRepository).save(any());
        var captor = ArgumentCaptor.forClass(Service.class);
        verify(eventProducer).publishRegistered(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("payments");
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void createDuplicateNameThrowsWithoutPersisting() {
        UUID tenantId = UUID.randomUUID();
        var req = new CreateServiceRequest("payments", null, null, null, null, null, null);
        when(serviceRepository.existsByName(tenantId, "payments", null)).thenReturn(true);

        assertThatThrownBy(() -> serviceService.create(tenantId, req, null))
                .isInstanceOf(DuplicateServiceNameException.class);

        verify(serviceRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    @Test
    void createWithUnknownTeamThrows() {
        UUID tenantId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        var req = new CreateServiceRequest("payments", null, teamId, null, null, null, null);
        when(serviceRepository.existsByName(tenantId, "payments", null)).thenReturn(false);
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceService.create(tenantId, req, null))
                .isInstanceOf(TeamNotFoundException.class);

        verify(serviceRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    @Test
    void createValidatesHealthEndpointWhenProvided() {
        UUID tenantId = UUID.randomUUID();
        var req = new CreateServiceRequest("payments", null, null, null, null, null, "http://example.com/health");
        Service saved = service(tenantId, "payments");
        when(serviceRepository.existsByName(tenantId, "payments", null)).thenReturn(false);
        when(serviceRepository.save(any())).thenReturn(saved);

        serviceService.create(tenantId, req, null);

        verify(healthEndpointValidator).validate("http://example.com/health");
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void updateSavesAndPublishesUpdatedEvent() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Service existing = service(tenantId, serviceId, "old");
        Service saved = service(tenantId, serviceId, "new");
        var req = new UpdateServiceRequest("new", null, null, null, null, null, null, null, null, null, null, null);
        when(serviceRepository.findById(tenantId, serviceId)).thenReturn(Optional.of(existing));
        when(serviceRepository.existsByName(tenantId, "new", serviceId)).thenReturn(false);
        when(serviceRepository.save(any())).thenReturn(saved);

        serviceService.update(tenantId, serviceId, req, null);

        verify(serviceRepository).save(any());
        verify(historyRepository).save(any());
        verify(eventProducer).publishUpdated(any());
    }

    @Test
    void updateNotFoundThrowsWithoutPublishing() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        var req = new UpdateServiceRequest("new", null, null, null, null, null, null, null, null, null, null, null);
        when(serviceRepository.findById(tenantId, serviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceService.update(tenantId, serviceId, req, null))
                .isInstanceOf(ServiceNotFoundException.class);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void updateDuplicateNameThrows() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Service existing = service(tenantId, serviceId, "old");
        var req = new UpdateServiceRequest("taken", null, null, null, null, null, null, null, null, null, null, null);
        when(serviceRepository.findById(tenantId, serviceId)).thenReturn(Optional.of(existing));
        when(serviceRepository.existsByName(tenantId, "taken", serviceId)).thenReturn(true);

        assertThatThrownBy(() -> serviceService.update(tenantId, serviceId, req, null))
                .isInstanceOf(DuplicateServiceNameException.class);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void updateOrphanServiceByNonAdminIsDenied() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", null, new SimpleGrantedAuthority("ROLE_VIEWER")));
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Service existing = service(tenantId, serviceId, "old"); // teamId is null (orphan)
        var req = new UpdateServiceRequest("new", null, null, null, null, null, null, null, null, null, null, null);
        when(serviceRepository.findById(tenantId, serviceId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> serviceService.update(tenantId, serviceId, req, userId))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void updateByOwningTeamMemberSucceeds() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", null, new SimpleGrantedAuthority("ROLE_VIEWER")));
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        Service existing = serviceWithTeam(tenantId, serviceId, "old", teamId);
        Service saved = serviceWithTeam(tenantId, serviceId, "new", teamId);
        var req = new UpdateServiceRequest("new", null, null, null, null, null, null, null, null, null, null, null);
        when(serviceRepository.findById(tenantId, serviceId)).thenReturn(Optional.of(existing));
        when(teamRepository.isMember(tenantId, teamId, userId)).thenReturn(true);
        when(serviceRepository.existsByName(tenantId, "new", serviceId)).thenReturn(false);
        when(serviceRepository.save(any())).thenReturn(saved);

        serviceService.update(tenantId, serviceId, req, userId);

        verify(eventProducer).publishUpdated(any());
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void deleteSoftDeletesAndPublishesDeletedEvent() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Service existing = service(tenantId, serviceId, "payments");
        when(serviceRepository.findById(tenantId, serviceId)).thenReturn(Optional.of(existing));

        serviceService.delete(tenantId, serviceId, null);

        verify(serviceRepository).softDelete(tenantId, serviceId);
        verify(historyRepository).save(any());
        var captor = ArgumentCaptor.forClass(Instant.class);
        verify(eventProducer).publishDeleted(eq(existing), captor.capture());
        assertThat(captor.getValue()).isNotNull();
    }

    @Test
    void deleteNotFoundThrowsWithoutPublishing() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        when(serviceRepository.findById(tenantId, serviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceService.delete(tenantId, serviceId, null))
                .isInstanceOf(ServiceNotFoundException.class);

        verifyNoInteractions(eventProducer);
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    void getNotFoundThrows() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        when(serviceRepository.findById(tenantId, serviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceService.get(tenantId, serviceId))
                .isInstanceOf(ServiceNotFoundException.class);
    }

    // ── assignOwner ───────────────────────────────────────────────────────────

    @Test
    void assignOwnerUpdatesTeamAndPublishesUpdatedEvent() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        Service existing = service(tenantId, serviceId, "payments");
        Service saved = serviceWithTeam(tenantId, serviceId, "payments", teamId);
        when(serviceRepository.findById(tenantId, serviceId)).thenReturn(Optional.of(existing));
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.of(team(tenantId, teamId)));
        when(serviceRepository.save(any())).thenReturn(saved);

        serviceService.assignOwner(tenantId, serviceId, teamId, null);

        var captor = ArgumentCaptor.forClass(Service.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().teamId()).isEqualTo(teamId);
        verify(eventProducer).publishUpdated(any());
    }

    @Test
    void assignOwnerServiceNotFoundThrows() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        when(serviceRepository.findById(tenantId, serviceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceService.assignOwner(tenantId, serviceId, UUID.randomUUID(), null))
                .isInstanceOf(ServiceNotFoundException.class);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void assignOwnerTeamNotFoundThrows() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        Service existing = service(tenantId, serviceId, "payments");
        when(serviceRepository.findById(tenantId, serviceId)).thenReturn(Optional.of(existing));
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceService.assignOwner(tenantId, serviceId, teamId, null))
                .isInstanceOf(TeamNotFoundException.class);

        verifyNoInteractions(eventProducer);
    }

    // ── resolveOwnership ──────────────────────────────────────────────────────

    @Test
    void resolveOwnershipAssignsTeamWhenServiceHasNoOwner() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        Service orphan = service(tenantId, serviceId, "payments");
        Team team = team(tenantId, teamId, "platform");
        Service withTeam = serviceWithTeam(tenantId, serviceId, "payments", teamId);
        var payload = new OwnershipResolvedPayload(tenantId, null, "org/repo", List.of("platform"), Map.of());

        when(serviceRepository.findByRepositoryPath(tenantId, "org/repo")).thenReturn(Optional.of(orphan));
        when(teamRepository.findByTenantAndName(tenantId, "platform")).thenReturn(Optional.of(team));
        when(teamRepository.findById(tenantId, teamId)).thenReturn(Optional.of(team));
        when(serviceRepository.findById(tenantId, serviceId)).thenReturn(Optional.of(orphan));
        when(serviceRepository.save(any())).thenReturn(withTeam);

        serviceService.resolveOwnership(payload);

        verify(serviceRepository).save(any());
        verify(eventProducer).publishUpdated(any());
    }

    @Test
    void resolveOwnershipSkipsWhenServiceNotFound() {
        UUID tenantId = UUID.randomUUID();
        var payload = new OwnershipResolvedPayload(tenantId, null, "org/repo", List.of("platform"), Map.of());
        when(serviceRepository.findByRepositoryPath(tenantId, "org/repo")).thenReturn(Optional.empty());

        serviceService.resolveOwnership(payload);

        verifyNoInteractions(eventProducer);
        verify(serviceRepository, never()).save(any());
    }

    @Test
    void resolveOwnershipSkipsWhenServiceAlreadyHasOwner() {
        UUID tenantId = UUID.randomUUID();
        Service owned = serviceWithTeam(tenantId, UUID.randomUUID(), "payments", UUID.randomUUID());
        var payload = new OwnershipResolvedPayload(tenantId, null, "org/repo", List.of("platform"), Map.of());
        when(serviceRepository.findByRepositoryPath(tenantId, "org/repo")).thenReturn(Optional.of(owned));

        serviceService.resolveOwnership(payload);

        verifyNoInteractions(eventProducer);
        verify(serviceRepository, never()).save(any());
    }

    @Test
    void resolveOwnershipSkipsWhenOwnerTeamsEmpty() {
        UUID tenantId = UUID.randomUUID();
        Service orphan = service(tenantId, "payments");
        var payload = new OwnershipResolvedPayload(tenantId, null, "org/repo", List.of(), Map.of());
        when(serviceRepository.findByRepositoryPath(tenantId, "org/repo")).thenReturn(Optional.of(orphan));

        serviceService.resolveOwnership(payload);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void resolveOwnershipSkipsWhenMultipleOwners() {
        UUID tenantId = UUID.randomUUID();
        Service orphan = service(tenantId, "payments");
        var payload = new OwnershipResolvedPayload(tenantId, null, "org/repo", List.of("team-a", "team-b"), Map.of());
        when(serviceRepository.findByRepositoryPath(tenantId, "org/repo")).thenReturn(Optional.of(orphan));

        serviceService.resolveOwnership(payload);

        verifyNoInteractions(eventProducer);
    }

    @Test
    void resolveOwnershipSkipsWhenTeamNotInRegistry() {
        UUID tenantId = UUID.randomUUID();
        Service orphan = service(tenantId, "payments");
        var payload = new OwnershipResolvedPayload(tenantId, null, "org/repo", List.of("unknown-team"), Map.of());
        when(serviceRepository.findByRepositoryPath(tenantId, "org/repo")).thenReturn(Optional.of(orphan));
        when(teamRepository.findByTenantAndName(tenantId, "unknown-team")).thenReturn(Optional.empty());

        serviceService.resolveOwnership(payload);

        verifyNoInteractions(eventProducer);
    }

    // ── countByConnectionId ───────────────────────────────────────────────────

    @Test
    void countByConnectionIdDelegatesToRepositoryAndReturnsResult() {
        UUID tenantId = UUID.randomUUID();
        UUID connId = UUID.randomUUID();
        when(serviceRepository.countByConnectionId(tenantId)).thenReturn(Map.of(connId, 5L));

        var result = serviceService.countByConnectionId(tenantId);

        assertThat(result).containsEntry(connId, 5L);
        verify(serviceRepository).countByConnectionId(tenantId);
    }

    @Test
    void countByConnectionIdReturnsEmptyMapWhenNoServices() {
        UUID tenantId = UUID.randomUUID();
        when(serviceRepository.countByConnectionId(tenantId)).thenReturn(Map.of());

        var result = serviceService.countByConnectionId(tenantId);

        assertThat(result).isEmpty();
    }

    // ── upsertDiscovered ──────────────────────────────────────────────────────

    @Test
    void upsertDiscoveredCreatesNewServiceAndRecordsHistory() {
        UUID tenantId = UUID.randomUUID();
        var command = new ServiceDiscoveryCommand(tenantId, null, "github", "ext-123",
                "payments", null, null, null, null, null, null,
                List.of(), "HEALTHY", null, null, null);
        when(serviceRepository.findByExternalId(tenantId, "ext-123")).thenReturn(Optional.empty());
        when(serviceRepository.findByName(tenantId, "payments")).thenReturn(Optional.empty());
        Service saved = service(tenantId, "payments");
        when(serviceRepository.save(any())).thenReturn(saved);

        serviceService.upsertDiscovered(command);

        verify(serviceRepository).save(any());
        verify(historyRepository).save(any());
        verifyNoInteractions(eventProducer);
    }

    @Test
    void upsertDiscoveredUpdatesExistingServiceAndRecordsHistory() {
        UUID tenantId = UUID.randomUUID();
        var command = new ServiceDiscoveryCommand(tenantId, null, "github", "ext-123",
                "payments", "new description", null, null, null, null, null,
                List.of(), "HEALTHY", null, null, null);
        Service existing = service(tenantId, "payments");
        when(serviceRepository.findByExternalId(tenantId, "ext-123")).thenReturn(Optional.of(existing));
        Service saved = service(tenantId, "payments");
        when(serviceRepository.save(any())).thenReturn(saved);

        serviceService.upsertDiscovered(command);

        verify(serviceRepository).save(any());
        verify(historyRepository).save(any());
    }

    @Test
    void upsertDiscoveredSkipsHistoryWhenNothingChanged() {
        UUID tenantId = UUID.randomUUID();
        // Command matches the existing service exactly — no field differs.
        Service existing = service(tenantId, "payments");
        var command = new ServiceDiscoveryCommand(tenantId, existing.connectionId(), existing.source(),
                existing.externalId(), existing.name(), existing.description(), existing.repositoryUrl(),
                existing.repositoryRef(), existing.k8sCluster(), existing.k8sNamespace(), existing.k8sDeployment(),
                existing.techStack() != null ? existing.techStack() : List.of(),
                existing.healthStatus().toDbValue(), existing.healthEndpoint(),
                existing.lastCommitAt(), existing.lastCommitSha());
        // service() helper has null source and null externalId, so the non-K8s path falls
        // through to findByName (externalId is null → skip findByExternalId).
        when(serviceRepository.findByName(tenantId, "payments")).thenReturn(Optional.of(existing));

        serviceService.upsertDiscovered(command);

        verify(serviceRepository, never()).save(any());
        verifyNoInteractions(historyRepository);
        verifyNoInteractions(eventProducer);
    }

    @Test
    void upsertDiscoveredValidatesHealthEndpoint() {
        UUID tenantId = UUID.randomUUID();
        var command = new ServiceDiscoveryCommand(tenantId, null, "github", "ext-123",
                "payments", null, null, null, null, null, null,
                List.of(), "HEALTHY", "http://example.com/health", null, null);
        when(serviceRepository.findByExternalId(tenantId, "ext-123")).thenReturn(Optional.empty());
        when(serviceRepository.findByName(tenantId, "payments")).thenReturn(Optional.empty());
        when(serviceRepository.save(any())).thenReturn(service(tenantId, "payments"));

        serviceService.upsertDiscovered(command);

        verify(healthEndpointValidator).validate("http://example.com/health");
    }

    @Test
    void upsertDiscoveredSkipsValidationForKubernetesSource() {
        UUID tenantId = UUID.randomUUID();
        var command = new ServiceDiscoveryCommand(tenantId, null, "kubernetes", null,
                "my-svc", null, null, null, null, "prod", null,
                List.of(), "HEALTHY", "http://my-svc.prod.svc.cluster.local:8080/actuator/health/ready", null, null);
        when(serviceRepository.findByK8sIdentity(tenantId, null, "prod", "my-svc")).thenReturn(Optional.empty());
        when(serviceRepository.findByNameForK8sClaim(tenantId, "my-svc")).thenReturn(Optional.empty());
        when(serviceRepository.save(any())).thenReturn(service(tenantId, "my-svc"));

        serviceService.upsertDiscovered(command);

        verifyNoInteractions(healthEndpointValidator);
    }

    @Test
    void upsertDiscoveredPreservesKubernetesHealthWhenScmSyncs() {
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();
        Service k8sSvc = new Service(UUID.randomUUID(), tenantId, "payments", null, null, null,
                null, null, ServiceHealthStatus.HEALTHY, null, now, now, null,
                "prod/payments", null, "kubernetes", null, "prod", "default", "payments-deploy",
                null, null, null, null, null, null, null, null, null);
        var scmCommand = new ServiceDiscoveryCommand(tenantId, null, "github", "prod/payments",
                "payments", null, "https://github.com/org/payments", null, null, null, null,
                List.of(), "UNKNOWN", null, null, null);
        when(serviceRepository.findByExternalId(tenantId, "prod/payments")).thenReturn(Optional.of(k8sSvc));
        when(serviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        serviceService.upsertDiscovered(scmCommand);

        var captor = org.mockito.ArgumentCaptor.forClass(Service.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().healthStatus()).isEqualTo(ServiceHealthStatus.HEALTHY);
        assertThat(captor.getValue().source()).isEqualTo("kubernetes");
    }

    @Test
    void upsertDiscoveredDoesNotDowngradeKubernetesHealthToUnknown() {
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();
        Service k8sSvc = new Service(UUID.randomUUID(), tenantId, "payments", null, null, null,
                null, null, ServiceHealthStatus.HEALTHY, null, now, now, null,
                "prod/payments", null, "kubernetes", null, "prod", "default", "payments-deploy",
                null, null, null, null, null, null, null, null, null);
        // description differs so a save is triggered — lets us inspect the preserved health status
        var k8sCommand = new ServiceDiscoveryCommand(tenantId, null, "kubernetes", null,
                "payments", "updated description", null, null, "prod", "default", "payments-deploy",
                List.of(), "UNKNOWN", null, null, null);
        when(serviceRepository.findByK8sIdentity(tenantId, "prod", "default", "payments")).thenReturn(Optional.of(k8sSvc));
        when(serviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        serviceService.upsertDiscovered(k8sCommand);

        var captor = org.mockito.ArgumentCaptor.forClass(Service.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().healthStatus()).isEqualTo(ServiceHealthStatus.HEALTHY);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Service service(UUID tenantId, String name) {
        return service(tenantId, UUID.randomUUID(), name);
    }

    private Service service(UUID tenantId, UUID id, String name) {
        Instant now = Instant.now();
        return new Service(id, tenantId, name, null, null, null, null, null,
                ServiceHealthStatus.UNKNOWN, null, now, now, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private Service serviceWithTeam(UUID tenantId, UUID id, String name, UUID teamId) {
        Instant now = Instant.now();
        return new Service(id, tenantId, name, null, teamId, null, null, null,
                ServiceHealthStatus.UNKNOWN, null, now, now, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private Team team(UUID tenantId, UUID teamId) {
        return team(tenantId, teamId, "team");
    }

    private Team team(UUID tenantId, UUID teamId, String name) {
        Instant now = Instant.now();
        return new Team(teamId, tenantId, name, now, now, null);
    }
}
