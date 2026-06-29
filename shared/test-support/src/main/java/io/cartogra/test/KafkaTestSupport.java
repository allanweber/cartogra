package io.cartogra.test;

import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

public final class KafkaTestSupport {
    private KafkaTestSupport() {}

    public static final ConfluentKafkaContainer KAFKA;

    static {
        ConfluentKafkaContainer container = new ConfluentKafkaContainer(
                DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
        container.withReuse(true);
        container.start();
        KAFKA = container;
    }
}
