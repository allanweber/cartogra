package io.cartogra.registry.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.common.identity.SystemActors;
import io.cartogra.registry.api.dto.CreateServiceRequest;
import io.cartogra.registry.api.dto.UpdateServiceRequest;
import io.cartogra.registry.repository.ServiceDiscoveryCommand;
import io.cartogra.registry.repository.ServiceFilter;
import io.cartogra.registry.infrastructure.validation.HealthEndpointValidator;
import io.cartogra.registry.repository.ServiceHistoryRepository;
import io.cartogra.registry.repository.ServiceRepository;
import io.cartogra.registry.repository.TeamRepository;
import io.cartogra.registry.domain.event.OwnershipResolvedPayload;
import io.cartogra.registry.domain.exception.DuplicateServiceNameException;
import io.cartogra.registry.domain.exception.PlanLimitExceededException;
import io.cartogra.registry.domain.exception.ServiceNotFoundException;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import io.cartogra.registry.infrastructure.kafka.ServiceLifecycleEventProducer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * All operations on the Service aggregate: CRUD, ownership assignment, orphan detection,
 * point-in-time history, discovery upsert, and CODEOWNERS-driven ownership resolution.
 */
@org.springframework.stereotype.Service
public class ServiceService {

    private static final Logger log = LoggerFactory.getLogger(ServiceService.class);

    private final ServiceRepository serviceRepository;
    private final ServiceHistoryRepository historyRepository;
    private final TeamRepository teamRepository;
    private final ObjectMapper objectMapper;
    private final ServiceLifecycleEventProducer eventProducer;
    private final HealthEndpointValidator healthEndpointValidator;
    private final PlanLimitService planLimitService;

    public ServiceService(ServiceRepository serviceRepository,
                          ServiceHistoryRepository historyRepository,
                          TeamRepository teamRepository,
                          ObjectMapper objectMapper,
                          ServiceLifecycleEventProducer eventProducer,
                          HealthEndpointValidator healthEndpointValidator,
                          PlanLimitService planLimitService) {
        this.serviceRepository = serviceRepository;
        this.historyRepository = historyRepository;
        this.teamRepository = teamRepository;
        this.objectMapper = objectMapper;
        this.eventProducer = eventProducer;
        this.healthEndpointValidator = healthEndpointValidator;
        this.planLimitService = planLimitService;
    }

    @Transactional
    public Service create(UUID tenantId, CreateServiceRequest req, @Nullable UUID requestedBy) {
        if (serviceRepository.existsByName(tenantId, req.name(), null)) {
            throw new DuplicateServiceNameException(req.name());
        }
        int maxServices = planLimitService.getLimits(tenantId).maxServices();
        if (maxServices != PlanLimits.UNLIMITED
                && serviceRepository.count(tenantId, ServiceFilter.empty()) >= maxServices) {
            throw new PlanLimitExceededException("services", maxServices);
        }
        if (req.teamId() != null) {
            teamRepository.findById(tenantId, req.teamId())
                    .orElseThrow(() -> new TeamNotFoundException(req.teamId()));
        }
        if (req.healthEndpoint() != null) {
            healthEndpointValidator.validate(req.healthEndpoint());
        }

        Instant now = Instant.now();
        var service = new Service(
                UUID.randomUUID(), tenantId, req.name(), req.description(),
                req.teamId(), req.repositoryUrl(), req.techStack(), req.metadata(),
                ServiceHealthStatus.UNKNOWN, null, now, now, null,
                null, null, null, null, null, null, null, req.healthEndpoint(), null, null, null,
                null, null, null, null, null
        );

        Service saved = serviceRepository.save(service);
        historyRepository.save(snapshot(saved, requestedBy));
        eventProducer.publishRegistered(saved);
        return saved;
    }

    @Transactional
    public Service update(UUID tenantId, UUID serviceId, UpdateServiceRequest req, @Nullable UUID requestedBy) {
        Service existing = serviceRepository.findById(tenantId, serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));
        requireAdminOrOwningTeamMember(tenantId, existing.teamId(), requestedBy);

        if (!existing.name().equalsIgnoreCase(req.name())
                && serviceRepository.existsByName(tenantId, req.name(), serviceId)) {
            throw new DuplicateServiceNameException(req.name());
        }
        boolean isKubernetesService = "kubernetes".equals(existing.source());

        // For kubernetes-sourced services, health status and health endpoint are owned exclusively
        // by the K8s cluster worker. Manual edits must never overwrite them.
        ServiceHealthStatus healthStatus;
        String healthEndpoint;
        if (isKubernetesService) {
            healthStatus = existing.healthStatus();
            healthEndpoint = existing.healthEndpoint();
        } else {
            if (req.healthEndpoint() != null) {
                healthEndpointValidator.validate(req.healthEndpoint());
            }
            healthStatus = req.healthStatus() != null
                    ? ServiceHealthStatus.fromString(req.healthStatus()) : existing.healthStatus();
            healthEndpoint = req.healthEndpoint() != null ? req.healthEndpoint() : existing.healthEndpoint();
        }

        String name              = req.name()             != null ? req.name()             : existing.name();
        String description       = req.description()      != null ? req.description()      : existing.description();
        String repositoryUrl     = req.repositoryUrl()    != null ? req.repositoryUrl()    : existing.repositoryUrl();
        List<String> techStack   = req.techStack()        != null ? req.techStack()        : existing.techStack();
        String metadata          = req.metadata()         != null ? req.metadata()         : existing.metadata();
        ServiceTier tier         = req.tier()             != null ? req.tier()             : existing.tier();
        List<String> tags        = req.tags()             != null ? req.tags()             : existing.tags();
        BigDecimal slaTarget     = req.slaTarget()        != null ? req.slaTarget()        : existing.slaTarget();
        String documentationUrl  = req.documentationUrl() != null ? req.documentationUrl() : existing.documentationUrl();
        String runbookUrl        = req.runbookUrl()       != null ? req.runbookUrl()       : existing.runbookUrl();

        boolean changed = !Objects.equals(existing.name(), name)
                || !Objects.equals(existing.description(), description)
                || !Objects.equals(existing.repositoryUrl(), repositoryUrl)
                || !Objects.equals(existing.techStack(), techStack)
                || !Objects.equals(existing.metadata(), metadata)
                || existing.healthStatus() != healthStatus
                || !Objects.equals(existing.healthEndpoint(), healthEndpoint)
                || existing.tier() != tier
                || !Objects.equals(existing.tags(), tags)
                || !Objects.equals(existing.slaTarget(), slaTarget)
                || !Objects.equals(existing.documentationUrl(), documentationUrl)
                || !Objects.equals(existing.runbookUrl(), runbookUrl);

        if (!changed) {
            return existing;
        }

        var updated = new Service(
                existing.id(), existing.tenantId(),
                name, description, existing.teamId(), repositoryUrl, techStack, metadata,
                healthStatus, existing.lastDeployedAt(), existing.createdAt(), Instant.now(), null,
                existing.externalId(), existing.connectionId(), existing.source(), existing.repositoryRef(),
                existing.k8sCluster(), existing.k8sNamespace(), existing.k8sDeployment(),
                healthEndpoint, existing.lastCommitAt(), existing.lastCommitSha(), existing.healthCheckedAt(),
                tier, tags, slaTarget, documentationUrl, runbookUrl
        );

        Service saved = serviceRepository.save(updated);
        historyRepository.save(snapshot(saved, requestedBy));
        eventProducer.publishUpdated(saved);
        return saved;
    }

    @Transactional
    public void delete(UUID tenantId, UUID serviceId, @Nullable UUID requestedBy) {
        Service existing = serviceRepository.findById(tenantId, serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));

        serviceRepository.softDelete(tenantId, serviceId);

        Instant deletedAt = Instant.now();
        var deleted = new Service(
                existing.id(), existing.tenantId(), existing.name(), existing.description(),
                existing.teamId(), existing.repositoryUrl(), existing.techStack(), existing.metadata(),
                existing.healthStatus(), existing.lastDeployedAt(), existing.createdAt(),
                deletedAt, deletedAt,
                existing.externalId(), existing.connectionId(), existing.source(), existing.repositoryRef(),
                existing.k8sCluster(), existing.k8sNamespace(), existing.k8sDeployment(),
                existing.healthEndpoint(), existing.lastCommitAt(), existing.lastCommitSha(),
                existing.healthCheckedAt(),
                existing.tier(), existing.tags(), existing.slaTarget(),
                existing.documentationUrl(), existing.runbookUrl()
        );
        historyRepository.save(snapshot(deleted, requestedBy));
        eventProducer.publishDeleted(existing, deletedAt);
    }

    public Service get(UUID tenantId, UUID serviceId) {
        return serviceRepository.findById(tenantId, serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));
    }

    public PageResult<Service> list(UUID tenantId, ServiceFilter filter, int limit, int offset) {
        List<Service> items = serviceRepository.findAll(tenantId, filter, limit, offset);
        long total = serviceRepository.count(tenantId, filter);
        return PageResult.of(items, total, limit, offset);
    }

    public PageResult<Service> detectOrphans(UUID tenantId, int limit, int offset) {
        List<Service> orphans = serviceRepository.findOrphaned(tenantId, limit, offset);
        return PageResult.of(orphans, orphans.size(), limit, offset);
    }

    @Transactional
    public Service assignOwner(UUID tenantId, UUID serviceId, @Nullable UUID teamId, @Nullable UUID requestedBy) {
        Service existing = serviceRepository.findById(tenantId, serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));

        if (teamId != null) {
            teamRepository.findById(tenantId, teamId)
                    .orElseThrow(() -> new TeamNotFoundException(teamId));
        }

        if (Objects.equals(existing.teamId(), teamId)) {
            return existing;
        }

        var updated = new Service(
                existing.id(), existing.tenantId(), existing.name(), existing.description(),
                teamId,
                existing.repositoryUrl(), existing.techStack(), existing.metadata(),
                existing.healthStatus(), existing.lastDeployedAt(), existing.createdAt(),
                Instant.now(), null,
                existing.externalId(), existing.connectionId(), existing.source(), existing.repositoryRef(),
                existing.k8sCluster(), existing.k8sNamespace(), existing.k8sDeployment(),
                existing.healthEndpoint(), existing.lastCommitAt(), existing.lastCommitSha(),
                existing.healthCheckedAt(),
                existing.tier(), existing.tags(), existing.slaTarget(),
                existing.documentationUrl(), existing.runbookUrl()
        );

        Service saved = serviceRepository.save(updated);
        historyRepository.save(snapshot(saved, requestedBy));
        eventProducer.publishUpdated(saved);
        return saved;
    }

    public PageResult<ServiceSnapshot> history(UUID tenantId, UUID serviceId, int limit, int offset) {
        serviceRepository.findById(tenantId, serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));
        List<ServiceSnapshot> items = historyRepository.findByServiceId(tenantId, serviceId, limit, offset);
        return PageResult.of(items, items.size(), limit, offset);
    }

    public List<String> listTechStacks(UUID tenantId) {
        return serviceRepository.findDistinctTechStacks(tenantId);
    }

    public java.util.Map<UUID, Long> countByConnectionId(UUID tenantId) {
        return serviceRepository.countByConnectionId(tenantId);
    }

    public Optional<ServiceSnapshot> historyAt(UUID tenantId, UUID serviceId, Instant at) {
        serviceRepository.findById(tenantId, serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));
        return historyRepository.findAtPointInTime(tenantId, serviceId, at);
    }

    @Transactional
    public void upsertDiscovered(ServiceDiscoveryCommand command) {
        boolean isKubernetesCommand = "kubernetes".equals(command.source());

        if (command.healthEndpoint() != null && !isKubernetesCommand) {
            healthEndpointValidator.validate(command.healthEndpoint());
        }

        // K8s uses its cluster/namespace/name triple as identity — externalId is SCM-owned.
        // SCM uses externalId, falling back to name for first contact.
        Optional<Service> existing;
        if (isKubernetesCommand) {
            existing = serviceRepository.findByK8sIdentity(
                    command.tenantId(), command.k8sCluster(), command.k8sNamespace(), command.name());
            if (existing.isEmpty()) {
                existing = serviceRepository.findByNameForK8sClaim(command.tenantId(), command.name());
            }
        } else {
            existing = command.externalId() != null
                    ? serviceRepository.findByExternalId(command.tenantId(), command.externalId())
                    : Optional.empty();
            if (existing.isEmpty()) {
                existing = serviceRepository.findByName(command.tenantId(), command.name());
            }
        }

        Instant now = Instant.now();

        if (existing.isPresent()) {
            Service current = existing.get();
            boolean isKubernetesService = "kubernetes".equals(current.source());

            ServiceHealthStatus healthStatus;
            String healthEndpoint;
            String source;
            // K8s never owns externalId or connectionId — those belong to SCM.
            String externalId;
            UUID connectionId;

            if (isKubernetesService && !isKubernetesCommand) {
                // SCM cannot override health or source of a kubernetes service.
                healthStatus   = current.healthStatus();
                healthEndpoint = current.healthEndpoint();
                source         = current.source();
                externalId     = command.externalId() != null ? command.externalId() : current.externalId();
                connectionId   = command.connectionId() != null ? command.connectionId() : current.connectionId();
            } else if (isKubernetesCommand) {
                ServiceHealthStatus incoming = ServiceHealthStatus.fromString(command.healthStatus());
                // Transient Endpoints API failure returns UNKNOWN — don't regress a service that
                // last had a definitive status; the next resync will correct it.
                healthStatus = (incoming == ServiceHealthStatus.UNKNOWN
                        && current.healthStatus() != ServiceHealthStatus.UNKNOWN)
                        ? current.healthStatus()
                        : incoming;
                healthEndpoint = command.healthEndpoint() != null ? command.healthEndpoint() : current.healthEndpoint();
                source         = command.source();
                // K8s never writes externalId or connectionId — preserve whatever SCM set.
                externalId   = current.externalId();
                connectionId = current.connectionId();
            } else {
                healthStatus   = ServiceHealthStatus.fromString(command.healthStatus());
                healthEndpoint = command.healthEndpoint() != null ? command.healthEndpoint() : current.healthEndpoint();
                source         = command.source();
                externalId     = command.externalId() != null ? command.externalId() : current.externalId();
                connectionId   = command.connectionId() != null ? command.connectionId() : current.connectionId();
            }

            String description   = command.description()   != null ? command.description()   : current.description();
            String repositoryUrl = command.repositoryUrl() != null ? command.repositoryUrl() : current.repositoryUrl();
            List<String> techStack = !command.techStack().isEmpty() ? command.techStack()    : current.techStack();
            String repositoryRef = command.repositoryRef() != null ? command.repositoryRef() : current.repositoryRef();
            String k8sCluster    = command.k8sCluster()    != null ? command.k8sCluster()    : current.k8sCluster();
            String k8sNamespace  = command.k8sNamespace()  != null ? command.k8sNamespace()  : current.k8sNamespace();
            String k8sDeployment = command.k8sDeployment() != null ? command.k8sDeployment() : current.k8sDeployment();
            Instant lastCommitAt = command.lastCommitAt()  != null ? command.lastCommitAt()  : current.lastCommitAt();
            String lastCommitSha = command.lastCommitSha() != null ? command.lastCommitSha() : current.lastCommitSha();

            boolean changed = !Objects.equals(current.description(), description)
                    || !Objects.equals(current.repositoryUrl(), repositoryUrl)
                    || !Objects.equals(current.techStack(), techStack)
                    || current.healthStatus() != healthStatus
                    || !Objects.equals(current.connectionId(), connectionId)
                    || !Objects.equals(current.source(), source)
                    || !Objects.equals(current.repositoryRef(), repositoryRef)
                    || !Objects.equals(current.k8sCluster(), k8sCluster)
                    || !Objects.equals(current.k8sNamespace(), k8sNamespace)
                    || !Objects.equals(current.k8sDeployment(), k8sDeployment)
                    || !Objects.equals(current.healthEndpoint(), healthEndpoint)
                    || !Objects.equals(current.lastCommitAt(), lastCommitAt)
                    || !Objects.equals(current.lastCommitSha(), lastCommitSha)
                    || !Objects.equals(current.externalId(), externalId);

            if (!changed) return;

            var updated = new Service(
                    current.id(), current.tenantId(), current.name(),
                    description, current.teamId(), repositoryUrl, techStack, current.metadata(),
                    healthStatus, current.lastDeployedAt(), current.createdAt(), now, null,
                    externalId, connectionId, source,
                    repositoryRef, k8sCluster, k8sNamespace, k8sDeployment,
                    healthEndpoint, lastCommitAt, lastCommitSha, current.healthCheckedAt(),
                    current.tier(), current.tags(), current.slaTarget(),
                    current.documentationUrl(), current.runbookUrl()
            );
            Service saved = serviceRepository.save(updated);
            historyRepository.save(snapshot(saved, SystemActors.SYSTEM));
        } else {
            // K8s never creates a row with externalId or connectionId.
            var created = new Service(
                    UUID.randomUUID(), command.tenantId(), command.name(), command.description(),
                    null, command.repositoryUrl(),
                    command.techStack().isEmpty() ? null : command.techStack(),
                    null,
                    ServiceHealthStatus.fromString(command.healthStatus()),
                    null, now, now, null,
                    isKubernetesCommand ? null : command.externalId(),
                    isKubernetesCommand ? null : command.connectionId(),
                    command.source(),
                    command.repositoryRef(), command.k8sCluster(), command.k8sNamespace(),
                    command.k8sDeployment(), command.healthEndpoint(),
                    command.lastCommitAt(), command.lastCommitSha(),
                    null, null, null, null, null, null
            );
            Service saved = serviceRepository.save(created);
            historyRepository.save(snapshot(saved, SystemActors.SYSTEM));
        }
    }

    /**
     * Auto-assigns a team to a service from a resolved CODEOWNERS mapping. Idempotent: skips when the
     * service already has an owner, ownership is ambiguous, or the team is unknown.
     */
    @Transactional
    public void resolveOwnership(OwnershipResolvedPayload payload) {
        Optional<Service> serviceOpt = serviceRepository.findByRepositoryPath(
                payload.tenantId(), payload.repositoryFullName());
        if (serviceOpt.isEmpty()) {
            log.warn("ownership.resolved: no service found for repository={} tenant={}",
                    payload.repositoryFullName(), payload.tenantId());
            return;
        }

        Service service = serviceOpt.get();
        if (service.teamId() != null) {
            log.warn("ownership.resolved: service={} already has team={}, skipping (idempotent)",
                    service.id(), service.teamId());
            return;
        }
        if (payload.ownerTeams().isEmpty()) {
            log.warn("ownership.resolved: no ownerTeams for repository={} tenant={}",
                    payload.repositoryFullName(), payload.tenantId());
            return;
        }
        if (payload.ownerTeams().size() > 1) {
            log.warn("ownership.resolved: ambiguous ownerTeams={} for repository={} tenant={}, skipping auto-assign",
                    payload.ownerTeams(), payload.repositoryFullName(), payload.tenantId());
            return;
        }

        String teamName = payload.ownerTeams().get(0);
        Optional<Team> teamOpt = teamRepository.findByTenantAndName(payload.tenantId(), teamName);
        if (teamOpt.isEmpty()) {
            log.warn("ownership.resolved: team='{}' not found in registry for tenant={}, repository={}",
                    teamName, payload.tenantId(), payload.repositoryFullName());
            return;
        }

        assignOwner(payload.tenantId(), service.id(), teamOpt.get().id(), SystemActors.SYSTEM);
        log.info("ownership.resolved: assigned team={} to service={} via CODEOWNERS",
                teamOpt.get().id(), service.id());
    }

    private ServiceSnapshot snapshot(Service service, @Nullable UUID changedBy) {
        try {
            String json = objectMapper.writeValueAsString(service);
            return new ServiceSnapshot(UUID.randomUUID(), service.id(), service.tenantId(), json, changedBy, Instant.now());
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize service snapshot", e);
        }
    }

    /**
     * ADMIN always passes. Otherwise the caller must be a member of the service's owning team.
     * An orphan service (teamId == null) has no team to be a member of, so only ADMIN may edit it.
     */
    private void requireAdminOrOwningTeamMember(UUID tenantId, @Nullable UUID teamId, @Nullable UUID requestedBy) {
        boolean isAdmin = SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (isAdmin) {
            return;
        }
        if (teamId == null || requestedBy == null || !teamRepository.isMember(tenantId, teamId, requestedBy)) {
            throw new AccessDeniedException("Only ADMIN or a member of the owning team may edit this service");
        }
    }
}
