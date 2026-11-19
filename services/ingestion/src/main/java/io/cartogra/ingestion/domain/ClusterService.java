package io.cartogra.ingestion.domain;

import io.cartogra.common.identity.Actor;
import io.cartogra.common.api.PageResult;
import io.cartogra.ingestion.api.dto.KubernetesClusterRequest;
import io.cartogra.ingestion.domain.exception.ClusterNotFoundException;
import io.cartogra.ingestion.domain.exception.DuplicateClusterNameException;
import io.cartogra.ingestion.domain.exception.PlanLimitExceededException;
import io.cartogra.ingestion.infrastructure.k8s.ClusterWorkerManager;
import io.cartogra.ingestion.infrastructure.k8s.CredentialEncryptor;
import io.cartogra.ingestion.infrastructure.k8s.KubeconfigParser;
import io.cartogra.ingestion.infrastructure.kafka.ServiceChangedProducer;
import io.cartogra.ingestion.infrastructure.registry.RegistryPlanLimitClient;
import io.cartogra.ingestion.infrastructure.registry.RegistryPlanLimits;
import io.cartogra.ingestion.repository.ClusterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ClusterService {

    private static final Logger log = LoggerFactory.getLogger(ClusterService.class);

    private final ClusterRepository repository;
    private final ClusterWorkerManager workerManager;
    private final CredentialEncryptor encryptor;
    private final KubeconfigParser kubeconfigParser;
    private final RegistryPlanLimitClient planLimitClient;
    private final IngestionAuditRecorder auditRecorder;
    private final ServiceChangedProducer serviceChangedProducer;

    public ClusterService(ClusterRepository repository,
                          ClusterWorkerManager workerManager,
                          CredentialEncryptor encryptor,
                          KubeconfigParser kubeconfigParser,
                          RegistryPlanLimitClient planLimitClient,
                          IngestionAuditRecorder auditRecorder,
                          ServiceChangedProducer serviceChangedProducer) {
        this.repository = repository;
        this.workerManager = workerManager;
        this.encryptor = encryptor;
        this.kubeconfigParser = kubeconfigParser;
        this.planLimitClient = planLimitClient;
        this.auditRecorder = auditRecorder;
        this.serviceChangedProducer = serviceChangedProducer;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Cluster create(UUID tenantId, Actor actor, KubernetesClusterRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (req.source() == null) {
            throw new IllegalArgumentException("source is required");
        }
        if (repository.existsByName(tenantId, req.name(), null)) {
            throw new DuplicateClusterNameException(req.name());
        }
        int maxK8sClusters = planLimitClient.fetchLimits(tenantId)
                .map(RegistryPlanLimits::maxK8sClusters)
                .orElse(RegistryPlanLimits.UNLIMITED);
        if (maxK8sClusters != RegistryPlanLimits.UNLIMITED && repository.count(tenantId) >= maxK8sClusters) {
            throw new PlanLimitExceededException("k8s clusters", maxK8sClusters);
        }

        String apiServerUrl;
        String caCertPem;
        String saToken;
        boolean skipTlsVerify;

        String rawKubeconfig = null;
        if (req.source() == ClusterSource.KUBECONFIG) {
            if (req.kubeconfig() == null || req.kubeconfig().isBlank()) {
                throw new IllegalArgumentException("kubeconfig is required when source is KUBECONFIG");
            }
            var parsed = kubeconfigParser.parse(req.kubeconfig());
            apiServerUrl = parsed.apiServerUrl();
            caCertPem = parsed.caCertPem();
            saToken = parsed.saToken();
            skipTlsVerify = parsed.skipTlsVerify();
            rawKubeconfig = req.kubeconfig();
        } else {
            if (req.apiServerUrl() == null || req.apiServerUrl().isBlank()) {
                throw new IllegalArgumentException("apiServerUrl is required when source is MANUAL");
            }
            if (req.saToken() == null || req.saToken().isBlank()) {
                throw new IllegalArgumentException("saToken is required when source is MANUAL");
            }
            apiServerUrl = req.apiServerUrl();
            caCertPem = req.caCertPem();
            saToken = req.saToken();
            skipTlsVerify = Boolean.TRUE.equals(req.skipTlsVerify());
        }

        Instant now = Instant.now();
        Cluster cluster = repository.save(new Cluster(
                UUID.randomUUID(),
                tenantId,
                req.name(),
                req.source(),
                apiServerUrl,
                caCertPem != null ? encryptor.encrypt(caCertPem) : null,
                saToken != null ? encryptor.encrypt(saToken) : null,
                skipTlsVerify,
                ClusterStatus.CONNECTING,
                null,
                now, now, null,
                rawKubeconfig != null ? encryptor.encrypt(rawKubeconfig) : null
        ));

        auditRecorder.clusterCreated(actor, cluster);
        workerManager.start(cluster);
        return cluster;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Cluster update(UUID tenantId, Actor actor, UUID id, KubernetesClusterRequest req) {
        Cluster existing = repository.findById(tenantId, id)
                .orElseThrow(() -> new ClusterNotFoundException(id));

        String apiServerUrl = existing.apiServerUrl();
        String caCertPem = existing.caCertPem();
        String saToken = existing.saToken();
        boolean skipTlsVerify = existing.skipTlsVerify();

        String kubeconfigEncrypted = existing.kubeconfigEncrypted();

        if (existing.source() == ClusterSource.KUBECONFIG) {
            if (req.kubeconfig() != null && !req.kubeconfig().isBlank()) {
                var parsed = kubeconfigParser.parse(req.kubeconfig());
                apiServerUrl = parsed.apiServerUrl();
                caCertPem = parsed.caCertPem() != null ? encryptor.encrypt(parsed.caCertPem()) : null;
                saToken = parsed.saToken() != null ? encryptor.encrypt(parsed.saToken()) : null;
                skipTlsVerify = parsed.skipTlsVerify();
                kubeconfigEncrypted = encryptor.encrypt(req.kubeconfig());
            }
        } else {
            if (req.apiServerUrl() != null && !req.apiServerUrl().isBlank()) apiServerUrl = req.apiServerUrl();
            if (req.caCertPem() != null) caCertPem = encryptor.encrypt(req.caCertPem());
            if (req.saToken() != null && !req.saToken().isBlank()) saToken = encryptor.encrypt(req.saToken());
            if (req.skipTlsVerify() != null) skipTlsVerify = req.skipTlsVerify();
        }

        String name = (req.name() != null && !req.name().isBlank()) ? req.name() : existing.name();
        if (!name.equalsIgnoreCase(existing.name()) && repository.existsByName(tenantId, name, id)) {
            throw new DuplicateClusterNameException(name);
        }

        Cluster updated = repository.save(new Cluster(
                existing.id(), existing.tenantId(), name, existing.source(),
                apiServerUrl, caCertPem, saToken, skipTlsVerify,
                ClusterStatus.CONNECTING, null,
                existing.createdAt(), Instant.now(), null,
                kubeconfigEncrypted
        ));

        auditRecorder.clusterUpdated(actor, existing, updated);
        workerManager.stop(id);
        if (!name.equals(existing.name())) {
            releaseServices(existing);
        }
        workerManager.start(updated);
        return updated;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public Cluster get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id)
                .orElseThrow(() -> new ClusterNotFoundException(id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public PageResult<Cluster> list(UUID tenantId, int limit, int offset) {
        long total = repository.count(tenantId);
        var items = repository.findAll(tenantId, limit, offset);
        return new PageResult<>(items, total, limit, offset);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void delete(UUID tenantId, Actor actor, UUID id) {
        Cluster existing = repository.findById(tenantId, id)
                .orElseThrow(() -> new ClusterNotFoundException(id));
        repository.softDelete(tenantId, id);
        auditRecorder.clusterDeleted(actor, existing);
        workerManager.stop(id);
        releaseServices(existing);
    }

    /**
     * An empty snapshot under the old name makes the registry remove that cluster from all its services. A Kafka
     * failure must not undo the cluster change, so it is logged and the services keep the old cluster name.
     */
    private void releaseServices(Cluster cluster) {
        try {
            serviceChangedProducer.publishResynced(
                    new ServiceResyncedPayload(cluster.tenantId(), cluster.name(), Instant.now(), List.of()));
        } catch (RuntimeException ex) {
            log.warn("Could not release services of cluster '{}' ({}) tenant={}: {}",
                    cluster.name(), cluster.id(), cluster.tenantId(), ex.getMessage());
        }
    }
}
