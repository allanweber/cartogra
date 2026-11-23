package io.cartogra.topology.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.domain.event.DependencyChangedPayload;
import io.cartogra.web.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Writes edge lifecycle events to the outbox in the caller's transaction, keyed by edge id. Every write to
 * {@code dependencies} that adds, changes, or removes a live edge must call it, or Contract's consumer list drifts.
 */
@Component
public class DependencyEventPublisher {

    public static final String TOPIC = "cartogra.topology.dependency.changed";
    public static final String DECLARED = "dependency.declared";
    public static final String OBSERVED = "dependency.observed";
    public static final String REMOVED = "dependency.removed";

    private final OutboxWriter outboxWriter;

    public DependencyEventPublisher(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    public void live(Dependency dependency) {
        publish(dependency.type() == DependencyType.DECLARED ? DECLARED : OBSERVED, dependency);
    }

    public void live(Collection<Dependency> dependencies) {
        outboxWriter.publishAll(TOPIC, dependencies.stream()
                .map(d -> envelope(d.type() == DependencyType.DECLARED ? DECLARED : OBSERVED, d))
                .toList());
    }

    public void removed(Dependency dependency) {
        publish(REMOVED, dependency);
    }

    public void removed(Collection<Dependency> dependencies) {
        outboxWriter.publishAll(TOPIC, dependencies.stream().map(d -> envelope(REMOVED, d)).toList());
    }

    private void publish(String eventType, Dependency dependency) {
        outboxWriter.publish(TOPIC, envelope(eventType, dependency));
    }

    private static EventEnvelope<DependencyChangedPayload> envelope(String eventType, Dependency dependency) {
        return EventEnvelope.of(eventType, dependency.id(), dependency.tenantId(), 1,
                DependencyChangedPayload.from(dependency));
    }
}
