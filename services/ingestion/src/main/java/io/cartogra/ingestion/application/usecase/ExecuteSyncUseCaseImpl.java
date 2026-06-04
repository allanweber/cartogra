package io.cartogra.ingestion.application.usecase;

import io.cartogra.common.event.SyncCommandPayload;
import io.cartogra.ingestion.application.port.out.CommitInfo;
import io.cartogra.ingestion.application.port.out.OwnershipMap;
import io.cartogra.ingestion.application.port.out.ScmConnectionConfig;
import io.cartogra.ingestion.application.port.out.ScmProvider;
import io.cartogra.ingestion.application.port.out.ScmProviderException;
import io.cartogra.ingestion.application.port.out.ScmRepository;
import io.cartogra.ingestion.application.port.out.ServiceDiscoveredPayload;
import io.cartogra.ingestion.application.port.out.SyncJobRepository;
import io.cartogra.ingestion.domain.SyncJob;
import io.cartogra.ingestion.infrastructure.kafka.OwnershipResolvedProducer;
import io.cartogra.ingestion.infrastructure.kafka.ServiceDiscoveredProducer;
import io.cartogra.ingestion.infrastructure.kafka.SyncResultProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ExecuteSyncUseCaseImpl implements ExecuteSyncUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExecuteSyncUseCaseImpl.class);

    private final Map<String, ScmProvider> providers;
    private final SyncJobRepository syncJobRepository;
    private final SyncResultProducer resultProducer;
    private final OwnershipResolvedProducer ownershipProducer;
    private final TechStackDetector techStackDetector;
    private final ServiceDiscoveredProducer serviceDiscoveredProducer;

    public ExecuteSyncUseCaseImpl(
            List<ScmProvider> providers,
            SyncJobRepository syncJobRepository,
            SyncResultProducer resultProducer,
            OwnershipResolvedProducer ownershipProducer,
            TechStackDetector techStackDetector,
            ServiceDiscoveredProducer serviceDiscoveredProducer) {
        this.providers = providers.stream()
                .collect(Collectors.toMap(ScmProvider::providerType, Function.identity()));
        this.syncJobRepository = syncJobRepository;
        this.resultProducer = resultProducer;
        this.ownershipProducer = ownershipProducer;
        this.techStackDetector = techStackDetector;
        this.serviceDiscoveredProducer = serviceDiscoveredProducer;
    }

    @Override
    public SyncJob execute(SyncCommandPayload command) {
        var running = syncJobRepository.findRunningForConnection(command.tenantId(), command.connectionId());
        if (running.isPresent()) {
            log.warn("Dropping duplicate sync command: a RUNNING job already exists for connection={}",
                    command.connectionId());
            return running.get();
        }

        SyncJob job = syncJobRepository.save(
                SyncJob.create(command.tenantId(), command.connectionId(), command.providerType()));
        syncJobRepository.markRunning(job.id());

        ScmProvider provider = providers.get(command.providerType());
        if (provider == null) {
            String msg = "No provider registered for type: " + command.providerType();
            syncJobRepository.markFailed(job.id(), msg);
            resultProducer.publishFailure(job, msg);
            throw new ScmProviderException(command.providerType(), msg);
        }

        ScmConnectionConfig connectionConfig = new ScmConnectionConfig(
                command.connectionId(),
                command.tenantId(),
                command.providerType(),
                command.connectionConfig()
        );

        try {
            List<ScmRepository> repositories = provider.listRepositories(connectionConfig);
            for (ScmRepository repo : repositories) {
                if (!repo.archived()) {
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
                        log.warn("Failed to publish service.discovered for repo={}: {}",
                                repo.fullPath(), ex.getMessage());
                    }
                    OwnershipMap ownership = provider.resolveOwnership(connectionConfig, repo);
                    ownershipProducer.publish(command.tenantId(), command.connectionId(), repo, ownership);
                }
            }
            int count = (int) repositories.stream().filter(r -> !r.archived()).count();
            syncJobRepository.markCompleted(job.id(), count);
            resultProducer.publishSuccess(job, count);
            log.info("Sync completed for connection={} repos={}", command.connectionId(), count);
            return syncJobRepository.findById(command.tenantId(), job.id()).orElse(job);
        } catch (ScmProviderException ex) {
            log.error("Sync failed for connection={}: {}", command.connectionId(), ex.getMessage());
            syncJobRepository.markFailed(job.id(), ex.getMessage());
            resultProducer.publishFailure(job, ex.getMessage());
            throw ex;
        } catch (Exception ex) {
            String msg = "Unexpected error during sync: " + ex.getMessage();
            log.error(msg, ex);
            syncJobRepository.markFailed(job.id(), msg);
            resultProducer.publishFailure(job, msg);
            throw new ScmProviderException(command.providerType(), msg, ex);
        }
    }
}
