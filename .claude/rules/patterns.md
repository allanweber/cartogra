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

---

## gRPC Server

File path: `services/{name}/src/main/java/io/cartogra/{name}/infrastructure/grpc/{Name}GrpcService.java`

The generated `*ImplBase` class comes from `shared:contracts`. Tenant ID is extracted from gRPC metadata by the server interceptor and stored in a `ScopedValue` — NEVER accept it as a request field.

```java
// infrastructure/grpc/RegistryGrpcService.java
import org.springframework.grpc.server.service.GrpcService;
import io.cartogra.grpc.registry.v1.*;
import io.grpc.stub.StreamObserver;

@GrpcService
public class RegistryGrpcService extends RegistryServiceGrpc.RegistryServiceImplBase {

    private final FindServiceUseCase findService;
    private final ListServicesUseCase listServices;

    public RegistryGrpcService(FindServiceUseCase findService, ListServicesUseCase listServices) {
        this.findService = findService;
        this.listServices = listServices;
    }

    @Override
    public void getService(GetServiceRequest request, StreamObserver<ServiceResponse> observer) {
        UUID tenantId = GrpcTenantContext.current(); // extracted by server interceptor
        ServiceDto dto = findService.findById(tenantId, UUID.fromString(request.getServiceId()));
        observer.onNext(RegistryMapper.toProto(dto));
        observer.onCompleted();
    }

    @Override
    public void watchServices(WatchServicesRequest request, StreamObserver<ServiceEvent> observer) {
        UUID tenantId = GrpcTenantContext.current();
        // Push events until the client cancels; call observer.onCompleted() when the stream ends naturally
        listServices.watch(tenantId, event -> {
            if (observer.isReady()) {
                observer.onNext(RegistryMapper.toProtoEvent(event));
            }
        });
    }
}
```

---

## gRPC Client

File path: `services/{name}/src/main/java/io/cartogra/{name}/infrastructure/grpc/{Target}GrpcClient.java`

Inject the channel via `@GrpcClient`. Attach `x-tenant-id` metadata on every call. Catch `StatusRuntimeException` and rethrow as a domain exception — NEVER let it reach the REST layer.

```java
// infrastructure/grpc/RegistryGrpcClient.java
import org.springframework.grpc.client.GrpcChannelFactory;
import io.cartogra.grpc.registry.v1.*;
import io.grpc.*;

@Component
public class RegistryGrpcClient {

    private static final Metadata.Key<String> TENANT_KEY =
        Metadata.Key.of("x-tenant-id", Metadata.ASCII_STRING_MARSHALLER);

    private final RegistryServiceGrpc.RegistryServiceBlockingStub stub;

    public RegistryGrpcClient(@GrpcClient("registry") Channel channel) {
        this.stub = RegistryServiceGrpc.newBlockingStub(channel);
    }

    public ServiceDto getService(UUID tenantId, UUID serviceId) {
        Metadata metadata = new Metadata();
        metadata.put(TENANT_KEY, tenantId.toString());
        try {
            ServiceResponse response = MetadataUtils.attachHeaders(stub, metadata)
                .getService(GetServiceRequest.newBuilder()
                    .setServiceId(serviceId.toString())
                    .build());
            return RegistryMapper.fromProto(response);
        } catch (StatusRuntimeException ex) {
            if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new ServiceNotFoundException(serviceId);
            }
            throw new InfrastructureException("registry gRPC call failed", ex);
        }
    }
}
```

Configure the channel target in `application.yml`:

```yaml
spring:
  grpc:
    client:
      channels:
        registry:
          address: ${REGISTRY_GRPC_HOST:localhost}:${REGISTRY_GRPC_PORT:9091}
```

Rules: `@GrpcService` on server · `@GrpcClient` on client channel param · tenant via metadata, never proto field · catch `StatusRuntimeException` at infrastructure boundary · OTel auto-instrumented via `spring-boot-starter-opentelemetry`
