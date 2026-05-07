---
mode: 'agent'
description: 'Scaffold Kafka topic config plus producer and/or consumer with OTel traceparent propagation'
---

You are working in the Cartogra monorepo. The full project rules are in `.github/copilot-instructions.md` — apply them to everything you generate.

Scaffold Kafka topic configuration plus producer and/or consumer boilerplate with OTel traceparent propagation.

**Usage:** provide `<service> <topic-suffix> <event-type> [producer|consumer|both]` (e.g., `registry service.registered both`)

## Steps

1. **Parse arguments**: service, topic suffix, event type, role (default: both)
   - Full topic name: `cartogra.<domain>.<topic-suffix>` (e.g., `cartogra.registry.service.registered`)

2. **Add topic config to `application.yml`**:
   ```yaml
   spring:
     kafka:
       bootstrap-servers: ${KAFKA_BROKERS:localhost:9092}
       producer:
         key-serializer: org.apache.kafka.common.serialization.StringSerializer
         value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
       consumer:
         group-id: ${spring.application.name}
         key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
         value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
         auto-offset-reset: earliest
   app:
     kafka:
       topics:
         <topic-key>: cartogra.<domain>.<topic-suffix>
   ```

3. **Define the event envelope record** in `domain/event/`:
   ```java
   public record <EventType>Event(
       UUID eventId,
       String eventType,
       UUID entityId,
       UUID tenantId,
       Instant timestamp,
       int version,
       UUID correlationId,
       <Payload>Payload payload
   ) {}
   ```

4. **Create Producer** in `infrastructure/kafka/` (if producer or both):
   ```java
   @Component
   public class <EventType>Producer {

       private final KafkaTemplate<String, Object> kafkaTemplate;
       private final String topic;

       public <EventType>Producer(KafkaTemplate<String, Object> kafkaTemplate,
                                  @Value("${app.kafka.topics.<topic-key>}") String topic) {
           this.kafkaTemplate = kafkaTemplate;
           this.topic = topic;
       }

       public void publish(<EventType>Event event) {
           ProducerRecord<String, Object> record =
               new ProducerRecord<>(topic, event.entityId().toString(), event);

           OpenTelemetry otel = GlobalOpenTelemetry.get();
           otel.getPropagators().getTextMapPropagator().inject(Context.current(), record.headers(),
               (headers, key, value) -> headers.add(key, value.getBytes(StandardCharsets.UTF_8)));

           kafkaTemplate.send(record);
       }
   }
   ```

5. **Create Consumer** in `infrastructure/kafka/` (if consumer or both):
   ```java
   @Component
   public class <EventType>Consumer {

       @KafkaListener(topics = "${app.kafka.topics.<topic-key>}", groupId = "${spring.kafka.consumer.group-id}")
       public void consume(ConsumerRecord<String, <EventType>Event> record) {
           OpenTelemetry otel = GlobalOpenTelemetry.get();
           Context ctx = otel.getPropagators().getTextMapPropagator()
               .extract(Context.current(), record.headers(),
                   (headers, key) -> {
                       Header h = headers.lastHeader(key);
                       return h != null ? new String(h.value(), StandardCharsets.UTF_8) : null;
                   });

           try (Scope _ = ctx.makeCurrent()) {
               process(record.value());
           }
       }

       private void process(<EventType>Event event) {
           // implement processing logic
       }
   }
   ```

6. **Verify rules checklist:**
   - [ ] Topic name follows `cartogra.{domain}.{entity}.{event}` pattern
   - [ ] Message key is the primary entity UUID
   - [ ] `traceparent` header injected in producer (W3C format via OTel propagator)
   - [ ] `traceparent` header extracted in consumer before processing
   - [ ] Event envelope includes: `event_id`, `event_type`, `entity_id`, `tenant_id`, `timestamp`, `version`, `correlation_id`, `payload`
   - [ ] Topic only introduced because both producer and consumer exist
