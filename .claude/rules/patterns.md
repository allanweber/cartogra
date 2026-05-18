# Code Patterns

Copy-ready skeletons. Apply the rules in `backend.md`, `frontend.md`, and `infra.md` when filling placeholders.

---

## REST Endpoint

```java
// api/controller/ServiceController.java
@RestController
@RequestMapping("/services")
public class ServiceController {

    private final RegisterServiceUseCase registerService;

    public ServiceController(RegisterServiceUseCase registerService) {
        this.registerService = registerService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ServiceDto>> register(
            @RequestHeader("X-Tenant-Id") UUID tenantId,
            @Valid @RequestBody RegisterServiceRequest request) {

        String traceId = Span.current().getSpanContext().getTraceId();
        ServiceDto result = registerService.execute(tenantId, request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>(result, traceId));
    }
}

// api/dto/ApiResponse.java
public record ApiResponse<T>(T data, String traceId) {}

// application/usecase/RegisterServiceUseCase.java
public interface RegisterServiceUseCase {
    ServiceDto execute(UUID tenantId, RegisterServiceRequest request);
}
```

---

## Flyway Migration

File path: `services/{name}/src/main/resources/db/migration/V00N__description.sql`

Check existing files first to get the correct next version number.

```sql
-- V001__create_services_table.sql
CREATE TABLE services (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    name        TEXT        NOT NULL,
    description TEXT,
    metadata    JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

CREATE INDEX ON services (tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX ON services USING GIN (metadata) WHERE metadata IS NOT NULL;

-- RLS safety net
ALTER TABLE services ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON services
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
```

Rules: UUID PK · TIMESTAMPTZ · `tenant_id UUID NOT NULL` · `deleted_at` for soft deletes · snake_case names

---

## Kafka Producer + Consumer

```java
// Event envelope record
public record ServiceRegisteredEvent(
    UUID eventId,
    String eventType,
    UUID entityId,
    UUID tenantId,
    Instant timestamp,
    int version,
    UUID correlationId,
    ServiceRegisteredPayload payload
) {
    public static ServiceRegisteredEvent of(UUID entityId, UUID tenantId, ServiceRegisteredPayload payload) {
        return new ServiceRegisteredEvent(
            UUID.randomUUID(), "service.registered", entityId, tenantId,
            Instant.now(), 1, UUID.randomUUID(), payload
        );
    }
}

// Producer — infrastructure/kafka/ServiceEventProducer.java
@Component
public class ServiceEventProducer {

    private final KafkaTemplate<String, ServiceRegisteredEvent> kafkaTemplate;

    public ServiceEventProducer(KafkaTemplate<String, ServiceRegisteredEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }
    private static final String TOPIC = "cartogra.registry.service.registered";

    public void publish(ServiceRegisteredEvent event) {
        ProducerRecord<String, ServiceRegisteredEvent> record =
            new ProducerRecord<>(TOPIC, event.entityId().toString(), event);

        // W3C traceparent propagation
        Context ctx = Context.current();
        W3CTraceContextPropagator.getInstance().inject(ctx, record.headers(),
            (headers, key, value) -> headers.add(key, value.getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(record);
    }
}

// Consumer — infrastructure/kafka/ServiceEventConsumer.java
@Component
public class ServiceEventConsumer {

    @KafkaListener(topics = "cartogra.registry.service.registered", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, ServiceRegisteredEvent> record) {
        // Extract W3C traceparent to restore trace context
        Context ctx = W3CTraceContextPropagator.getInstance().extract(Context.current(), record.headers(),
            (headers, key) -> {
                Header h = headers.lastHeader(key);
                return h != null ? new String(h.value(), StandardCharsets.UTF_8) : null;
            });

        try (Scope _ = ctx.makeCurrent()) {
            // process event
        }
    }
}
```

---

## React Component

```tsx
// frontend/src/components/ServiceList.tsx
import { useQuery } from '@tanstack/react-query'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { apiFetch } from '@/lib/api'
import type { Service } from '@/types'

export function ServiceList() {
  const { data: services, isLoading, error } = useQuery({
    queryKey: ['services'],
    queryFn: () => apiFetch<Service[]>('/registry/services'),
  })

  if (isLoading) {
    return (
      <div className="space-y-2">
        {Array.from({ length: 3 }).map((_, i) => (
          <Skeleton key={i} className="h-16 w-full" />
        ))}
      </div>
    )
  }

  if (error) {
    return (
      <Alert variant="destructive">
        <AlertDescription>
          {error.message}
          {error instanceof ApiError && ` (trace: ${error.traceId})`}
        </AlertDescription>
      </Alert>
    )
  }

  return (
    <div className="space-y-4">
      {services?.map((service) => (
        <Card key={service.id}>
          <CardHeader>
            <CardTitle>{service.name}</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-muted-foreground">{service.description}</p>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}
```

Rules: named export · TanStack Query · shadcn/ui · `.data` already extracted by `apiFetch` · loading + error states

---

## React Page (TanStack Start)

```tsx
// frontend/src/routes/services/index.tsx
import { createFileRoute } from '@tanstack/react-router'
import { AppLayout } from '@/components/AppLayout'
import { ServiceList } from '@/components/ServiceList'

export const Route = createFileRoute('/services/')({
  component: ServicesPage,
})

function ServicesPage() {
  return (
    <AppLayout>
      <div className="space-y-6">
        <h1 className="text-2xl font-semibold">Services</h1>
        <ServiceList />
      </div>
    </AppLayout>
  )
}
```

Rules: `createFileRoute` · file under `frontend/src/routes/` · `AppLayout` wrapper · data fetching delegated to child components using TanStack Query

