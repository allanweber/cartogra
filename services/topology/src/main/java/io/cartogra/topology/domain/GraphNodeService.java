package io.cartogra.topology.domain;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.topology.domain.event.ServiceLifecyclePayload;
import io.cartogra.topology.domain.exception.BackfillFailedException;
import io.cartogra.topology.infrastructure.registry.RegistryGraphNodeClient;
import io.cartogra.topology.infrastructure.registry.RegistryServiceSnapshot;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;

@Service
public class GraphNodeService {

    private static final String EVENT_TYPE_DELETED = "service.deleted";
    private static final int BACKFILL_PAGE_SIZE = 200;

    private static final Logger log = LoggerFactory.getLogger(GraphNodeService.class);

    private final GraphNodeRepository graphNodeRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final RegistryGraphNodeClient registryClient;

    public GraphNodeService(GraphNodeRepository graphNodeRepository,
                             ProcessedEventRepository processedEventRepository,
                             RegistryGraphNodeClient registryClient) {
        this.graphNodeRepository = graphNodeRepository;
        this.processedEventRepository = processedEventRepository;
        this.registryClient = registryClient;
    }

    /**
     * Applies a registered/updated/deleted envelope to {@code graph_nodes}. Idempotent:
     * the (tenantId, eventId) dedupe check and the mutation share one transaction, so a
     * replayed envelope — or a retried one that already committed — is a no-op.
     */
    @Transactional
    public void applyLifecycleEvent(EventEnvelope<ServiceLifecyclePayload> envelope) {
        boolean firstTime = processedEventRepository.markProcessed(envelope.tenantId(), envelope.eventId());
        if (!firstTime) {
            log.debug("Skipping already-processed envelope eventId={} eventType={}",
                    envelope.eventId(), envelope.eventType());
            return;
        }

        ServiceLifecyclePayload payload = envelope.payload();
        if (EVENT_TYPE_DELETED.equals(envelope.eventType())) {
            Instant deletedAt = payload.deletedAt() != null ? payload.deletedAt() : envelope.timestamp();
            graphNodeRepository.softDelete(payload.tenantId(), payload.id(), deletedAt);
        } else {
            graphNodeRepository.upsert(payload.toGraphNodeUpsert());
        }
    }

    /**
     * Walks every tenant's active services from Registry once, upserting each into
     * {@code graph_nodes}. Backs {@code POST /internal/backfill} — seeds tenants that
     * predate this consumer, whose registered/updated events were never published. Each
     * page's upserts are naturally idempotent (keyed by tenantId+serviceId), so this is
     * safe to re-run.
     */
    public int backfill() {
        int upserted = 0;
        int offset = 0;
        List<RegistryServiceSnapshot> page;
        do {
            try {
                page = registryClient.listActiveServices(BACKFILL_PAGE_SIZE, offset);
            } catch (RestClientException e) {
                throw new BackfillFailedException(upserted, offset, e);
            }
            for (RegistryServiceSnapshot snapshot : page) {
                graphNodeRepository.upsert(snapshot.toGraphNodeUpsert());
                upserted++;
            }
            offset += BACKFILL_PAGE_SIZE;
        } while (page.size() == BACKFILL_PAGE_SIZE);

        log.info("Backfill upserted {} graph nodes from registry", upserted);
        return upserted;
    }
}
