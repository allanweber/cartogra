package io.cartogra.topology.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

/**
 * The span topics are written by the collector, so the broker may auto-create them with its 7-day
 * default; {@code spring.kafka.admin.modify-topic-configs} lets these beans fix retention in place.
 * Segments roll at the retention too: Kafka only deletes closed segments, and the 1 GiB default
 * would otherwise keep a quiet topic's data forever.
 */
@Configuration
@EnableConfigurationProperties({SpanLinkProperties.class, SpanTopicProperties.class})
public class SpanLinkConfig {

    public static final String SPANS_TOPIC = "cartogra.observability.spans";

    @Bean
    public NewTopic spansTopic(SpanTopicProperties properties) {
        return withRetention(SPANS_TOPIC, properties.retention());
    }

    @Bean
    public NewTopic spansDlqTopic(SpanTopicProperties properties) {
        return withRetention(SPANS_TOPIC + ".dlq", properties.dlqRetention());
    }

    private static NewTopic withRetention(String name, Duration retention) {
        return TopicBuilder.name(name)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(retention.toMillis()))
                .config(TopicConfig.SEGMENT_MS_CONFIG, String.valueOf(retention.toMillis()))
                .build();
    }
}
