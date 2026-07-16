package io.cartogra.ingestion.domain;

import io.cartogra.common.event.SyncCommandPayload;
import io.cartogra.ingestion.infrastructure.k8s.CredentialEncryptor;
import io.cartogra.ingestion.repository.ScmConnectionRepository;
import io.cartogra.ingestion.infrastructure.scm.ScmProvider;
import io.cartogra.ingestion.domain.exception.ScmProviderException;
import io.cartogra.ingestion.repository.SyncJobRepository;
import io.cartogra.ingestion.infrastructure.kafka.OwnershipResolvedProducer;
import io.cartogra.ingestion.infrastructure.kafka.ServiceDiscoveredProducer;
import io.cartogra.ingestion.infrastructure.kafka.SyncResultProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Executes a sync command end-to-end: list repos, detect tech stack, publish
 * service.discovered + ownership.resolved events, and record the outcome back
 * onto the originating {@code scm_connections} row (closing the feedback loop).
 */
@Service
public class SyncExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SyncExecutionService.class);

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final TypeReference<Map<String, Object>> CONFIG_MAP_TYPE = new TypeReference<>() {};

    private final Map<String, ScmProvider> providers;
    private final SyncJobRepository syncJobRepository;
    private final ScmConnectionRepository scmConnectionRepository;
    private final SyncResultProducer resultProducer;
    private final OwnershipResolvedProducer ownershipProducer;
    private final TechStackDetector techStackDetector;
    private final ServiceDiscoveredProducer serviceDiscoveredProducer;
    private final CredentialEncryptor credentialEncryptor;
    private final ObjectMapper objectMapper;

    public SyncExecutionService(
            List<ScmProvider> providers,
            SyncJobRepository syncJobRepository,
            ScmConnectionRepository scmConnectionRepository,
            SyncResultProducer resultProducer,
            OwnershipResolvedProducer ownershipProducer,
            TechStackDetector techStackDetector,
            ServiceDiscoveredProducer serviceDiscoveredProducer,
            CredentialEncryptor credentialEncryptor,
            ObjectMapper objectMapper) {
        this.providers = providers.stream()
                .collect(Collectors.toMap(ScmProvider::providerType, Function.identity()));
        this.syncJobRepository = syncJobRepository;
        this.scmConnectionRepository = scmConnectionRepository;
        this.resultProducer = resultProducer;
        this.ownershipProducer = ownershipProducer;
        this.techStackDetector = techStackDetector;
        this.serviceDiscoveredProducer = serviceDiscoveredProducer;
        this.credentialEncryptor = credentialEncryptor;
        this.objectMapper = objectMapper;
    }

    public Optional<SyncJob> execute(SyncCommandPayload command) {
        // The Kafka payload carries only an id (plan 013 — never ship secrets over Kafka), so
        // fetch-and-decrypt the connection's config from the source of truth at execution time.
        // The connection may have been deleted between publish and consume; skip gracefully.
        Optional<ScmConnection> connectionOpt =
                scmConnectionRepository.findById(command.tenantId(), command.connectionId());
        if (connectionOpt.isEmpty()) {
            log.warn("Skipping sync command: connection={} (tenant={}) no longer exists",
                    command.connectionId(), command.tenantId());
            return Optional.empty();
        }
        ScmConnection connection = connectionOpt.get();

        var running = syncJobRepository.findRunningForConnection(command.tenantId(), command.connectionId());
        if (running.isPresent()) {
            log.warn("Dropping duplicate sync command: a RUNNING job already exists for connection={}",
                    command.connectionId());
            return Optional.of(running.get());
        }

        SyncJob job = syncJobRepository.save(
                SyncJob.create(command.tenantId(), command.connectionId(), command.providerType()));
        syncJobRepository.markRunning(job.id());

        ScmProvider provider = providers.get(command.providerType());
        if (provider == null) {
            String msg = "No provider registered for type: " + command.providerType();
            syncJobRepository.markFailed(job.id(), msg);
            resultProducer.publishFailure(job, msg);
            recordResult(command.connectionId(), STATUS_FAILED, msg);
            throw new ScmProviderException(command.providerType(), msg);
        }

        ScmConnectionConfig connectionConfig = new ScmConnectionConfig(
                command.connectionId(),
                command.tenantId(),
                command.providerType(),
                decryptSecrets(connection.config())
        );

        try {
            List<ScmRepository> repositories = provider.listRepositories(connectionConfig);
            List<ScmRepository> active = repositories.stream().filter(r -> !r.archived()).toList();

            // Each repo does ~10 blocking SCM API calls (tech-stack detection, last commit,
            // CODEOWNERS); virtual threads let a sync of many repos run in parallel instead
            // of paying that cost serially, repo by repo.
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = active.stream()
                        .<Future<?>>map(repo -> executor.submit(() -> processRepo(command, provider, connectionConfig, repo)))
                        .toList();
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new ScmProviderException(command.providerType(), "Sync interrupted", ie);
                    } catch (ExecutionException ee) {
                        switch (ee.getCause()) {
                            case ScmProviderException spe -> throw spe;
                            case RuntimeException re -> throw re;
                            case Throwable t -> throw new ScmProviderException(
                                    command.providerType(), "Unexpected error during sync", t);
                        }
                    }
                }
            }

            int count = active.size();
            syncJobRepository.markCompleted(job.id(), count);
            resultProducer.publishSuccess(job, count);
            recordResult(command.connectionId(), STATUS_SUCCESS, null);
            log.info("Sync completed for connection={} repos={}", command.connectionId(), count);
            return Optional.of(syncJobRepository.findById(command.tenantId(), job.id()).orElse(job));
        } catch (ScmProviderException ex) {
            log.error("Sync failed for connection={}: {}", command.connectionId(), ex.getMessage());
            syncJobRepository.markFailed(job.id(), ex.getMessage());
            resultProducer.publishFailure(job, ex.getMessage());
            recordResult(command.connectionId(), STATUS_FAILED, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            String msg = "Unexpected error during sync: " + ex.getMessage();
            log.error(msg, ex);
            syncJobRepository.markFailed(job.id(), msg);
            resultProducer.publishFailure(job, msg);
            recordResult(command.connectionId(), STATUS_FAILED, msg);
            throw new ScmProviderException(command.providerType(), msg, ex);
        }
    }

    private void processRepo(SyncCommandPayload command, ScmProvider provider,
                              ScmConnectionConfig connectionConfig, ScmRepository repo) {
        try {
            List<String> techStack = techStackDetector.detect(provider, connectionConfig, repo);
            Optional<CommitInfo> commit = provider.getLastCommit(connectionConfig, repo);
            var payload = new ServiceDiscoveredPayload(
                    command.tenantId(),
                    command.connectionId(),
                    command.providerType(),
                    repo.fullPath(),
                    repo.name(),
                    repo.description(),
                    repo.repositoryUrl(),
                    repo.defaultBranch(),
                    null, null, null,
                    techStack,
                    "UNKNOWN",
                    null,
                    commit.map(CommitInfo::committedAt).orElse(null),
                    commit.map(CommitInfo::sha).orElse(null)
            );
            serviceDiscoveredProducer.publish(payload);
        } catch (Exception ex) {
            log.warn("Failed to publish service.discovered for repo={}: {}", repo.fullPath(), ex.getMessage());
        }
        OwnershipMap ownership = provider.resolveOwnership(connectionConfig, repo);
        ownershipProducer.publish(command.tenantId(), command.connectionId(), repo, ownership);
    }

    /**
     * Parses the connection's {@code config} JSON (fetched fresh from {@link ScmConnectionRepository}
     * at execution time — plan 013 stopped shipping it over Kafka) and decrypts any
     * {@link ScmConnectionService#SECRET_KEYS} value, which is still ciphertext at rest, before
     * handing the config to a {@link ScmProvider} REST client.
     */
    private Map<String, Object> decryptSecrets(String rawConfig) {
        Map<String, Object> decrypted = new LinkedHashMap<>(objectMapper.readValue(rawConfig, CONFIG_MAP_TYPE));
        for (String key : ScmConnectionService.SECRET_KEYS) {
            if (decrypted.get(key) instanceof String s) {
                decrypted.put(key, credentialEncryptor.decrypt(s));
            }
        }
        return decrypted;
    }

    /** Best-effort feedback write; a stale connection row must not fail the sync. */
    private void recordResult(java.util.UUID connectionId, String status, String error) {
        try {
            scmConnectionRepository.updateSyncResult(connectionId, status, error, Instant.now());
        } catch (Exception ex) {
            log.warn("Failed to record sync result for connection={}: {}", connectionId, ex.getMessage());
        }
    }
}
