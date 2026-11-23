package io.cartogra.topology.infrastructure.scheduled;

import io.cartogra.web.outbox.OutboxRelay;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelayScheduler {

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${topology.outbox.relay-interval:PT1S}",
            initialDelayString = "${topology.outbox.relay-interval:PT1S}")
    public void tick() {
        relay.relayBatch();
    }
}
