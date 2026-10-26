package io.cartogra.topology.domain;

import io.cartogra.topology.config.SpanLinkProperties;
import io.cartogra.topology.domain.exception.UnattributedSpanBatchException;
import io.cartogra.topology.domain.exception.UnattributedSpanBatchException.QuarantineReason;
import io.cartogra.topology.infrastructure.scheduled.DependencyGraphViewRefreshScheduler;
import io.cartogra.topology.repository.DependencyRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.MessagingEndpoint;
import io.cartogra.topology.repository.PendingSpanLink;
import io.cartogra.topology.repository.PendingSpanLinkRepository;
import io.cartogra.topology.repository.SpanRef;
import io.cartogra.topology.repository.UpsertOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class ObservedDependencyService {

    private record EdgeKey(UUID source, UUID target, DependencyProtocol protocol) {}

    private static final Comparator<EdgeKey> EDGE_LOCK_ORDER = Comparator.comparing(EdgeKey::source)
            .thenComparing(EdgeKey::target)
            .thenComparing(EdgeKey::protocol);

    private final GraphNodeRepository graphNodeRepository;
    private final DependencyRepository dependencyRepository;
    private final PendingSpanLinkRepository pendingSpanLinkRepository;
    private final UnresolvedSpanServiceService unresolvedSpanServiceService;
    private final DependencyGraphViewRefreshScheduler graphViewRefreshScheduler;
    private final SpanLinkProperties spanLinkProperties;
    private final MeterRegistry meterRegistry;

    public ObservedDependencyService(GraphNodeRepository graphNodeRepository,
                                     DependencyRepository dependencyRepository,
                                     PendingSpanLinkRepository pendingSpanLinkRepository,
                                     UnresolvedSpanServiceService unresolvedSpanServiceService,
                                     DependencyGraphViewRefreshScheduler graphViewRefreshScheduler,
                                     SpanLinkProperties spanLinkProperties,
                                     MeterRegistry meterRegistry) {
        this.graphNodeRepository = graphNodeRepository;
        this.dependencyRepository = dependencyRepository;
        this.pendingSpanLinkRepository = pendingSpanLinkRepository;
        this.unresolvedSpanServiceService = unresolvedSpanServiceService;
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
        List<String> unknownNames = new ArrayList<>();
        List<SpanObservation> pendingClients = new ArrayList<>();
        List<SpanObservation> pendingServers = new ArrayList<>();
        Set<SpanRef> resolvedClientSpans = new HashSet<>();
        Set<MessagingEndpoint> messagingEndpoints = new LinkedHashSet<>();

        for (SpanObservation span : observations) {
            if (span.protocol() == null) {
                count("cartogra.spans.skipped", "reason", "unsupported_protocol");
                continue;
            }
            UUID ownId = names.resolve(span.serviceName());
            if (ownId == null) {
                unknownNames.add(span.serviceName());
                count("cartogra.spans.skipped", "reason", "unresolved_source");
                continue;
            }
            switch (span.kind()) {
                case CLIENT -> {
                    if (span.protocol() == DependencyProtocol.DB) {
                        handleDb(span, ownId, names, edges);
                    } else {
                        handleClient(tenantId, span, ownId, names, edges, pendingClients, pendingServers,
                                unknownNames, resolvedClientSpans);
                    }
                }
                case SERVER -> handleServer(tenantId, span, ownId, names, edges, pendingClients, pendingServers,
                        resolvedClientSpans);
                case PRODUCER, CONSUMER -> collectMessagingEndpoint(span, messagingEndpoints);
            }
        }

        discardServersOfResolvedClients(tenantId, pendingServers, resolvedClientSpans);
        flushPending(tenantId, pendingClients, pendingServers, now);
        joinMessaging(tenantId, messagingEndpoints, names, edges, now);
        upsertEdges(tenantId, edges, now);
        unresolvedSpanServiceService.record(tenantId, unknownNames, now);
    }

    private void handleClient(UUID tenantId, SpanObservation client, UUID sourceId, NameResolver names,
                              Set<EdgeKey> edges, List<SpanObservation> pendingClients,
                              List<SpanObservation> pendingServers, List<String> unknownNames,
                              Set<SpanRef> resolvedClientSpans) {
        String targetName = client.peerName() != null ? client.peerName() : client.peerHost();
        UUID targetId = targetName != null ? names.resolve(targetName) : null;
        if (targetId != null) {
            resolvedClientSpans.add(new SpanRef(client.traceId(), client.spanId()));
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
        Optional<PendingSpanLink> stored =
                pendingSpanLinkRepository.takeServerPartner(tenantId, client.traceId(), client.spanId());
        if (stored.isPresent()) {
            matchedFallback(edges, sourceId, names.resolve(stored.get().serviceName()), client.protocol());
            return;
        }
        if (client.peerName() != null) {
            unknownNames.add(client.peerName());
        }
        pendingClients.add(client);
    }

    private void handleServer(UUID tenantId, SpanObservation server, UUID targetId, NameResolver names,
                              Set<EdgeKey> edges, List<SpanObservation> pendingClients,
                              List<SpanObservation> pendingServers, Set<SpanRef> resolvedClientSpans) {
        if (server.parentSpanId() == null
                || resolvedClientSpans.contains(new SpanRef(server.traceId(), server.parentSpanId()))) {
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
        Optional<PendingSpanLink> stored =
                pendingSpanLinkRepository.takeClientPartner(tenantId, server.traceId(), server.parentSpanId());
        if (stored.isPresent()) {
            PendingSpanLink link = stored.get();
            DependencyProtocol protocol = link.protocol() != null ? link.protocol() : server.protocol();
            matchedFallback(edges, names.resolve(link.serviceName()), targetId, protocol);
            return;
        }
        pendingServers.add(server);
    }

    /** A DB never emits a SERVER span, so an unmatched DB is dropped instead of parked or recorded. */
    private void handleDb(SpanObservation client, UUID sourceId, NameResolver names, Set<EdgeKey> edges) {
        UUID targetId = Stream.of(client.peerName(), client.dbName(), client.peerHost())
                .filter(Objects::nonNull)
                .map(names::resolve)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (targetId == null) {
            count("cartogra.spans.skipped", "reason", "unresolved_db");
            return;
        }
        addEdge(edges, sourceId, targetId, DependencyProtocol.DB);
    }

    private void collectMessagingEndpoint(SpanObservation span, Set<MessagingEndpoint> endpoints) {
        if (span.destination() == null) {
            count("cartogra.spans.skipped", "reason", "unsupported_protocol");
            return;
        }
        endpoints.add(new MessagingEndpoint(span.destination(), span.kind(), span.serviceName()));
    }

    /**
     * Joins producers and consumers on the destination name: every producer gets a kafka edge to every
     * consumer of the same destination, as long as one side was seen in this batch. A side missed in a race
     * is joined by the next span on either side, because messaging rows live for {@code messagingTtl}.
     */
    private void joinMessaging(UUID tenantId, Set<MessagingEndpoint> batch, NameResolver names,
                               Set<EdgeKey> edges, Instant now) {
        if (batch.isEmpty()) {
            return;
        }
        Set<MessagingEndpoint> known = pendingSpanLinkRepository.joinMessagingEndpoints(tenantId, batch,
                now.plus(spanLinkProperties.messagingTtl()));
        names.preload(known.stream().map(MessagingEndpoint::serviceName).toList());

        for (MessagingEndpoint producer : known) {
            if (producer.kind() != SpanKind.PRODUCER) {
                continue;
            }
            for (MessagingEndpoint consumer : known) {
                if (consumer.kind() == SpanKind.CONSUMER
                        && consumer.destination().equals(producer.destination())
                        && (batch.contains(producer) || batch.contains(consumer))) {
                    UUID sourceId = names.resolve(producer.serviceName());
                    UUID targetId = names.resolve(consumer.serviceName());
                    if (sourceId == null || targetId == null) {
                        count("cartogra.spans.skipped", "reason", "unresolved_target");
                        continue;
                    }
                    count("cartogra.spans.messaging_matched");
                    addEdge(edges, sourceId, targetId, DependencyProtocol.KAFKA);
                }
            }
        }
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

    /** A SERVER half parked before its CLIENT resolved directly can never be matched; drop it now, not at TTL. */
    private void discardServersOfResolvedClients(UUID tenantId, List<SpanObservation> pendingServers,
                                                 Set<SpanRef> resolvedClientSpans) {
        if (resolvedClientSpans.isEmpty()) {
            return;
        }
        pendingServers.removeIf(s -> resolvedClientSpans.contains(new SpanRef(s.traceId(), s.parentSpanId())));
        int deleted = pendingSpanLinkRepository.deleteServerPartners(tenantId, resolvedClientSpans);
        if (deleted > 0) {
            meterRegistry.counter("cartogra.spans.pending_discarded").increment(deleted);
        }
    }

    private void flushPending(UUID tenantId, List<SpanObservation> clients, List<SpanObservation> servers,
                              Instant now) {
        Instant expiresAt = now.plus(spanLinkProperties.ttl());
        pendingSpanLinkRepository.park(tenantId, Stream.concat(clients.stream(), servers.stream())
                .map(span -> pendingLink(tenantId, span, expiresAt))
                .toList());
    }

    private PendingSpanLink pendingLink(UUID tenantId, SpanObservation span, Instant expiresAt) {
        return new PendingSpanLink(UUID.randomUUID(), tenantId, span.traceId(), span.spanId(), span.parentSpanId(),
                span.kind(), span.serviceName(), span.protocol(), expiresAt);
    }

    private void upsertEdges(UUID tenantId, Set<EdgeKey> edges, Instant now) {
        boolean touched = false;
        List<EdgeKey> ordered = edges.stream().sorted(EDGE_LOCK_ORDER).toList();
        for (EdgeKey edge : ordered) {
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
                if (span.dbName() != null) {
                    initial.add(lower(span.dbName()));
                }
            }
            this.resolved = new HashMap<>(graphNodeRepository.findLiveByLowerNames(tenantId, initial));
            this.lookedUp.addAll(initial);
        }

        void preload(Collection<String> names) {
            List<String> missing = names.stream().map(NameResolver::lower).filter(lookedUp::add).toList();
            if (!missing.isEmpty()) {
                resolved.putAll(graphNodeRepository.findLiveByLowerNames(tenantId, missing));
            }
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
