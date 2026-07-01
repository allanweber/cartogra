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
import io.cartogra.registry.domain.exception.ServiceNotFoundException;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import io.cartogra.registry.infrastructure.kafka.ServiceLifecycleEventProducer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
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

    public ServiceService(ServiceRepository serviceRepository,
                          ServiceHistoryRepository historyRepository,
                          TeamRepository teamRepository,
                          ObjectMapper objectMapper,
                          ServiceLifecycleEventProducer eventProducer,
                          HealthEndpointValidator healthEndpointValidator) {
        this.serviceRepository = serviceRepository;
        this.historyRepository = historyRepository;
        this.teamRepository = teamRepository;
        this.objectMapper = objectMapper;
        this.eventProducer = eventProducer;
        this.healthEndpointValidator = healthEndpointValidator;
    }

    @Transactional
    public Service create(UUID tenantId, CreateServiceRequest req, @Nullable UUID requestedBy) {
        if (serviceRepository.existsByName(tenantId, req.name(), null)) {
            throw new DuplicateServiceNameException(req.name());
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
    @PreAuthorize("hasAnyRole('ADMIN', 'TEAM_OWNER')")
    public Service update(UUID tenantId, UUID serviceId, UpdateServiceRequest req, @Nullable UUID requestedBy) {
        Service existing = serviceRepository.findById(tenantId, serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));

        if (!existing.name().equalsIgnoreCase(req.name())
                && serviceRepository.existsByName(tenantId, req.name(), serviceId)) {
            throw new DuplicateServiceNameException(req.name());
        }
        if (req.healthEndpoint() != null) {
            healthEndpointValidator.validate(req.healthEndpoint());
        }

        String name              = req.name()             != null ? req.name()             : existing.name();
        String description       = req.description()      != null ? req.description()      : existing.description();
        String repositoryUrl     = req.repositoryUrl()    != null ? req.repositoryUrl()    : existing.repositoryUrl();
        List<String> techStack   = req.techStack()        != null ? req.techStack()        : existing.techStack();
        String metadata          = req.metadata()         != null ? req.metadata()         : existing.metadata();
        ServiceHealthStatus healthStatus = req.healthStatus() != null
                ? ServiceHealthStatus.fromString(req.healthStatus()) : existing.healthStatus();
        String healthEndpoint    = req.healthEndpoint()   != null ? req.healthEndpoint()   : existing.healthEndpoint();
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

    public Optional<ServiceSnapshot> historyAt(UUID tenantId, UUID serviceId, Instant at) {
        serviceRepository.findById(tenantId, serviceId)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));
        return historyRepository.findAtPointInTime(tenantId, serviceId, at);
    }

    @Transactional
    public void upsertDiscovered(ServiceDiscoveryCommand command) {
        if (command.healthEndpoint() != null) {
            healthEndpointValidator.validate(command.healthEndpoint());
        }
        serviceRepository.upsertDiscovered(command)
                .ifPresent(saved -> historyRepository.save(snapshot(saved, SystemActors.SYSTEM)));
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
}
