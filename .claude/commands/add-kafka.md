You are working in the Cartogra monorepo. Read AGENTS.md for all project rules before proceeding.

Scaffold Kafka topic configuration plus producer and/or consumer boilerplate with OTel traceparent propagation.

Arguments: $ARGUMENTS
(Expected: `<service> <topic-suffix> <event-type> [producer|consumer|both]` — e.g., `/add-kafka registry service.registered both`)

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
       UUID eventId,          // UUIDv5
       String eventType,      // e.g., "service.registered"
       UUID entityId,
       UUID tenantId,
       Instant timestamp,
       int version,
       UUID correlationId,
       Object payload         // or typed payload record
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

           // Propagate OTel traceparent
           OpenTelemetry otel = GlobalOpenTelemetry.get();
           TextMapPropagator propagator = otel.getPropagators().getTextMapPropagator();
           propagator.inject(Context.current(), record.headers(),
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
           // Extract OTel traceparent from headers
           OpenTelemetry otel = GlobalOpenTelemetry.get();
           Context ctx = otel.getPropagators().getTextMapPropagator()
               .extract(Context.current(), record.headers(),
                   (headers, key) -> {
                       Header h = headers.lastHeader(key);
                       return h != null ? new String(h.value(), StandardCharsets.UTF_8) : null;
                   });

           try (Scope scope = ctx.makeCurrent()) {
               // process event
               process(record.value());
           }
       }

       private void process(<EventType>Event event) {
           // implement processing logic
       }
   }
   ```

6. **Verify rules checklist before finishing:**
   - [ ] Topic name follows `cartogra.{domain}.{entity}.{event}` pattern
   - [ ] Message key is the primary entity UUID (partition ordering)
   - [ ] `traceparent` header is injected in producer (W3C format via OTel propagator)
   - [ ] `traceparent` header is extracted in consumer before processing
   - [ ] Event envelope includes: `event_id`, `event_type`, `entity_id`, `tenant_id`, `timestamp`, `version`, `correlation_id`, `payload`
   - [ ] Topic is only introduced because there is a real producer AND consumer (no speculative topics)
   - [ ] `event_id` is UUIDv5 (deterministic, based on entity + timestamp or equivalent)
