package io.cartogra.test;

import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

public final class KafkaTestSupport {
    private KafkaTestSupport() {}
    @SuppressWarnings("resource")
    public static ConfluentKafkaContainer create() {
        return new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    }
}
