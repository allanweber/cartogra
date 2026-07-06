package io.cartogra.ingestion.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.ingestion.api.dto.KubernetesClusterRequest;
import io.cartogra.ingestion.domain.exception.ClusterNotFoundException;
import io.cartogra.ingestion.infrastructure.k8s.ClusterWorkerManager;
import io.cartogra.ingestion.infrastructure.k8s.CredentialEncryptor;
import io.cartogra.ingestion.infrastructure.k8s.KubeconfigParser;
import io.cartogra.ingestion.repository.ClusterRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class ClusterService {

    private final ClusterRepository repository;
    private final ClusterWorkerManager workerManager;
    private final CredentialEncryptor encryptor;
    private final KubeconfigParser kubeconfigParser;

    public ClusterService(ClusterRepository repository,
                          ClusterWorkerManager workerManager,
                          CredentialEncryptor encryptor,
                          KubeconfigParser kubeconfigParser) {
        this.repository = repository;
        this.workerManager = workerManager;
        this.encryptor = encryptor;
        this.kubeconfigParser = kubeconfigParser;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Cluster create(UUID tenantId, KubernetesClusterRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (req.source() == null) {
            throw new IllegalArgumentException("source is required");
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

        workerManager.start(cluster);
        return cluster;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Cluster update(UUID tenantId, UUID id, KubernetesClusterRequest req) {
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
            if (req.saToken() != null) saToken = encryptor.encrypt(req.saToken());
            if (req.skipTlsVerify() != null) skipTlsVerify = req.skipTlsVerify();
        }

        String name = (req.name() != null && !req.name().isBlank()) ? req.name() : existing.name();

        Cluster updated = repository.save(new Cluster(
                existing.id(), existing.tenantId(), name, existing.source(),
                apiServerUrl, caCertPem, saToken, skipTlsVerify,
                ClusterStatus.CONNECTING, null,
                existing.createdAt(), Instant.now(), null,
                kubeconfigEncrypted
        ));

        workerManager.stop(id);
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
    public void delete(UUID tenantId, UUID id) {
        repository.findById(tenantId, id)
                .orElseThrow(() -> new ClusterNotFoundException(id));
        repository.softDelete(tenantId, id);
        workerManager.stop(id);
    }
}
