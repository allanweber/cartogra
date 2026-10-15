package io.cartogra.topology.domain;

import io.cartogra.topology.config.SpanLinkProperties;
import io.cartogra.topology.domain.exception.UnattributedSpanBatchException;
import io.cartogra.topology.domain.exception.UnattributedSpanBatchException.QuarantineReason;
import io.cartogra.topology.infrastructure.scheduled.DependencyGraphViewRefreshScheduler;
import io.cartogra.topology.repository.DependencyRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.PendingSpanLink;
import io.cartogra.topology.repository.PendingSpanLinkRepository;
import io.cartogra.topology.repository.UnresolvedSpanServiceRepository;
import io.cartogra.topology.repository.UpsertOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ObservedDependencyService {

    private record EdgeKey(UUID source, UUID target, DependencyProtocol protocol) {}

    private final GraphNodeRepository graphNodeRepository;
    private final DependencyRepository dependencyRepository;
    private final PendingSpanLinkRepository pendingSpanLinkRepository;
    private final UnresolvedSpanServiceRepository unresolvedSpanServiceRepository;
    private final DependencyGraphViewRefreshScheduler graphViewRefreshScheduler;
    private final SpanLinkProperties spanLinkProperties;
    private final MeterRegistry meterRegistry;

    public ObservedDependencyService(GraphNodeRepository graphNodeRepository,
                                     DependencyRepository dependencyRepository,
                                     PendingSpanLinkRepository pendingSpanLinkRepository,
                                     UnresolvedSpanServiceRepository unresolvedSpanServiceRepository,
                                     DependencyGraphViewRefreshScheduler graphViewRefreshScheduler,
                                     SpanLinkProperties spanLinkProperties,
                                     MeterRegistry meterRegistry) {
        this.graphNodeRepository = graphNodeRepository;
        this.dependencyRepository = dependencyRepository;
        this.pendingSpanLinkRepository = pendingSpanLinkRepository;
        this.unresolvedSpanServiceRepository = unresolvedSpanServiceRepository;
        this.graphViewRefreshScheduler = graphViewRefreshScheduler;
        this.spanLinkProperties = spanLinkProperties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Validates the unauthenticated {@code cartogra.tenant.id} span attribute. Every failure bumps
     * the quarantine counter and throws, which the Kafka error handler routes to the DLQ.
     */
    public UUID requireAttributedTenant(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            throw quarantine(QuarantineReason.TENANT_MISSING);
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(raw.trim());
        } catch (IllegalArgumentException _) {
            throw quarantine(QuarantineReason.TENANT_MALFORMED);
        }
        if (!graphNodeRepository.existsLiveForTenant(tenantId)) {
            throw quarantine(QuarantineReason.TENANT_UNKNOWN);
        }
        return tenantId;
    }

    @Transactional
    public void ingest(UUID tenantId, List<SpanObservation> observations) {
        Instant now = Instant.now();
        NameResolver names = new NameResolver(tenantId, observations);
        Set<EdgeKey> edges = new LinkedHashSet<>();
        Set<String> recordedUnknown = new HashSet<>();
        List<SpanObservation> pendingClients = new ArrayList<>();
        List<SpanObservation> pendingServers = new ArrayList<>();
        Set<String> resolvedClientSpans = new HashSet<>();
        FallbackLock lock = new FallbackLock(tenantId);

        for (SpanObservation span : observations) {
            if (span.protocol() == null) {
                count("cartogra.spans.skipped", "reason", "unsupported_protocol");
                continue;
            }
            UUID ownId = names.resolve(span.serviceName());
            if (ownId == null) {
                recordUnknown(tenantId, span.serviceName(), now, recordedUnknown);
                count("cartogra.spans.skipped", "reason", "unresolved_source");
                continue;
            }
            if (span.kind() == SpanKind.CLIENT) {
                handleClient(tenantId, span, ownId, names, edges, pendingClients, pendingServers, now,
                        recordedUnknown, resolvedClientSpans, lock);
            } else {
                handleServer(tenantId, span, ownId, names, edges, pendingClients, pendingServers,
                        resolvedClientSpans, lock);
            }
        }

        flushPending(tenantId, pendingClients, pendingServers, now);
        upsertEdges(tenantId, edges, now);
    }

    private void handleClient(UUID tenantId, SpanObservation client, UUID sourceId, NameResolver names,
                              Set<EdgeKey> edges, List<SpanObservation> pendingClients,
                              List<SpanObservation> pendingServers, Instant now, Set<String> recordedUnknown,
                              Set<String> resolvedClientSpans, FallbackLock lock) {
        String targetName = client.peerName() != null ? client.peerName() : client.peerHost();
        UUID targetId = targetName != null ? names.resolve(targetName) : null;
        if (targetId != null) {
            resolvedClientSpans.add(spanKey(client.traceId(), client.spanId()));
            addEdge(edges, sourceId, targetId, client.protocol());
            return;
        }

        Optional<SpanObservation> inBatch = pendingServers.stream()
                .filter(s -> s.traceId().equals(client.traceId()) && client.spanId().equals(s.parentSpanId()))
                .findFirst();
        if (inBatch.isPresent()) {
            pendingServers.remove(inBatch.get());
            matchedFallback(edges, sourceId, names.resolve(inBatch.get().serviceName()), client.protocol());
            return;
        }
        lock.acquire();
        Optional<PendingSpanLink> stored =
                pendingSpanLinkRepository.findServerPartner(tenantId, client.traceId(), client.spanId());
        if (stored.isPresent()) {
            pendingSpanLinkRepository.delete(stored.get().id());
            matchedFallback(edges, sourceId, names.resolve(stored.get().serviceName()), client.protocol());
            return;
        }
        if (client.peerName() != null) {
            recordUnknown(tenantId, client.peerName(), now, recordedUnknown);
        }
        pendingClients.add(client);
    }

    private void handleServer(UUID tenantId, SpanObservation server, UUID targetId, NameResolver names,
                              Set<EdgeKey> edges, List<SpanObservation> pendingClients,
                              List<SpanObservation> pendingServers, Set<String> resolvedClientSpans,
                              FallbackLock lock) {
        if (server.parentSpanId() == null
                || resolvedClientSpans.contains(spanKey(server.traceId(), server.parentSpanId()))) {
            return;
        }
        Optional<SpanObservation> inBatch = pendingClients.stream()
                .filter(c -> c.traceId().equals(server.traceId()) && c.spanId().equals(server.parentSpanId()))
                .findFirst();
        if (inBatch.isPresent()) {
            pendingClients.remove(inBatch.get());
            matchedFallback(edges, names.resolve(inBatch.get().serviceName()), targetId, inBatch.get().protocol());
            return;
        }
        lock.acquire();
        Optional<PendingSpanLink> stored =
                pendingSpanLinkRepository.findClientPartner(tenantId, server.traceId(), server.parentSpanId());
        if (stored.isPresent()) {
            PendingSpanLink link = stored.get();
            pendingSpanLinkRepository.delete(link.id());
            DependencyProtocol protocol = link.protocol() != null
                    ? DependencyProtocol.fromDbValue(link.protocol()) : server.protocol();
            matchedFallback(edges, names.resolve(link.serviceName()), targetId, protocol);
            return;
        }
        pendingServers.add(server);
    }

    private static String spanKey(String traceId, String spanId) {
        return traceId + '/' + spanId;
    }

    private void matchedFallback(Set<EdgeKey> edges, @Nullable UUID sourceId, @Nullable UUID targetId,
                                 @Nullable DependencyProtocol protocol) {
        if (sourceId == null || targetId == null) {
            count("cartogra.spans.skipped", "reason", "unresolved_target");
            return;
        }
        count("cartogra.spans.fallback_matched");
        addEdge(edges, sourceId, targetId, protocol);
    }

    private void addEdge(Set<EdgeKey> edges, UUID sourceId, UUID targetId, @Nullable DependencyProtocol protocol) {
        if (protocol == null) {
            count("cartogra.spans.skipped", "reason", "unsupported_protocol");
        } else if (sourceId.equals(targetId)) {
            count("cartogra.spans.skipped", "reason", "self");
        } else {
            edges.add(new EdgeKey(sourceId, targetId, protocol));
        }
    }

    private void recordUnknown(UUID tenantId, String name, Instant now, Set<String> recordedUnknown) {
        if (recordedUnknown.add(name.toLowerCase(Locale.ROOT))) {
            unresolvedSpanServiceRepository.record(tenantId, name, now, spanLinkProperties.maxUnresolvedPerTenant());
        }
    }

    private void flushPending(UUID tenantId, List<SpanObservation> clients, List<SpanObservation> servers,
                              Instant now) {
        Instant expiresAt = now.plus(spanLinkProperties.ttl());
        for (SpanObservation client : clients) {
            pendingSpanLinkRepository.insert(pendingLink(tenantId, client, expiresAt));
        }
        for (SpanObservation server : servers) {
            pendingSpanLinkRepository.insert(pendingLink(tenantId, server, expiresAt));
        }
    }

    private PendingSpanLink pendingLink(UUID tenantId, SpanObservation span, Instant expiresAt) {
        return new PendingSpanLink(UUID.randomUUID(), tenantId, span.traceId(), span.spanId(), span.parentSpanId(),
                span.kind(), span.serviceName(),
                span.protocol() != null ? span.protocol().toDbValue() : null, expiresAt);
    }

    private void upsertEdges(UUID tenantId, Set<EdgeKey> edges, Instant now) {
        boolean touched = false;
        for (EdgeKey edge : edges) {
            UpsertOutcome outcome = dependencyRepository.upsertObserved(
                    tenantId, edge.source(), edge.target(), edge.protocol(), now);
            if (outcome != UpsertOutcome.UNCHANGED) {
                touched = true;
                count("cartogra.spans.edges", "result", outcome.name().toLowerCase(Locale.ROOT));
            }
        }
        if (touched) {
            graphViewRefreshScheduler.markDirty();
        }
    }

    private UnattributedSpanBatchException quarantine(QuarantineReason reason) {
        count("cartogra.spans.quarantined", "reason", reason.name().toLowerCase(Locale.ROOT));
        return new UnattributedSpanBatchException(reason);
    }

    private void count(String name, String... tags) {
        meterRegistry.counter(name, tags).increment();
    }

    /**
     * Serializes the find-partner/insert-pending join per tenant for the rest of the transaction.
     * One lock per tenant (not per trace) keeps lock-table usage constant and rules out deadlocks;
     * without it two instances handling the halves of one call can each miss the other's insert.
     */
    private final class FallbackLock {
        private final UUID tenantId;
        private boolean held;

        FallbackLock(UUID tenantId) {
            this.tenantId = tenantId;
        }

        void acquire() {
            if (!held) {
                pendingSpanLinkRepository.lockTenant(tenantId);
                held = true;
            }
        }
    }

    private final class NameResolver {
        private final UUID tenantId;
        private final Map<String, UUID> resolved;
        private final Set<String> lookedUp = new HashSet<>();

        NameResolver(UUID tenantId, List<SpanObservation> observations) {
            this.tenantId = tenantId;
            Set<String> initial = new HashSet<>();
            for (SpanObservation span : observations) {
                initial.add(lower(span.serviceName()));
                if (span.peerName() != null) {
                    initial.add(lower(span.peerName()));
                }
                if (span.peerHost() != null) {
                    initial.add(lower(span.peerHost()));
                }
            }
            this.resolved = new HashMap<>(graphNodeRepository.findLiveByLowerNames(tenantId, initial));
            this.lookedUp.addAll(initial);
        }

        @Nullable
        UUID resolve(String name) {
            String key = lower(name);
            if (lookedUp.add(key)) {
                resolved.putAll(graphNodeRepository.findLiveByLowerNames(tenantId, List.of(key)));
            }
            return resolved.get(key);
        }

        private static String lower(String name) {
            return name.toLowerCase(Locale.ROOT);
        }
    }
}
