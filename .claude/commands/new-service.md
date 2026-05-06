You are working in the Cartogra monorepo. Read AGENTS.md for all project rules before proceeding.

Scaffold a new Spring Boot microservice following Cartogra conventions.

Arguments: $ARGUMENTS
(Expected: `<service-name>` — e.g., `/new-service analytics`)

## Steps

1. **Determine package and paths**
   - Service name (kebab-case): `$ARGUMENTS`
   - Java package: `io.cartogra.<name>` (camelCase, no hyphens)
   - Root path: `services/<name>/`

2. **Create Gradle module** — `services/<name>/build.gradle.kts`:
   ```kotlin
   plugins {
       id("org.springframework.boot")
       id("io.spring.dependency-management")
       kotlin("jvm")
   }
   dependencies {
       implementation(project(":shared:common"))
       implementation("org.springframework.boot:spring-boot-starter-web")
       implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
       implementation("org.springframework.boot:spring-boot-starter-actuator")
       implementation("org.springframework.boot:spring-boot-starter-validation")
       implementation("org.flywaydb:flyway-core")
       implementation("io.micrometer:micrometer-tracing-bridge-otel")
       implementation("io.opentelemetry:opentelemetry-exporter-otlp")
       runtimeOnly("org.postgresql:postgresql")
       testImplementation("org.springframework.boot:spring-boot-starter-test")
       // gRPC — include both if the service exposes AND calls internal gRPC APIs
       // Remove either block if the service only acts as server or only as client
       implementation(project(":shared:contracts"))
       implementation("org.springframework.grpc:spring-grpc-spring-boot-starter:$springGrpcVersion")
   }
   ```

3. **Create hexagonal package structure** under `src/main/java/io/cartogra/<name>/`:
   - `api/` — Controllers, request/response records, mappers
   - `domain/` — Aggregate roots, value objects, domain events
   - `application/` — Use case interfaces and implementations
   - `infrastructure/` — JDBC repositories, Kafka producers/consumers, gRPC clients and server implementations
   - `config/` — Spring beans, security config, OTel config

4. **Create `Application.java`** in the root package:
   ```java
   @SpringBootApplication
   public class <Name>Application {
       public static void main(String[] args) {
           SpringApplication.run(<Name>Application.class, args);
       }
   }
   ```

5. **Create `src/main/resources/application.yml`**:
   ```yaml
   spring:
     application:
       name: <name>-service
     datasource:
       url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5436}/${DB_NAME:<name>_db}
       username: ${DB_USER:cartogra}
       password: ${DB_PASSWORD:secret}
     flyway:
       enabled: true
       locations: classpath:db/migration
   management:
     endpoints:
       web:
         exposure:
           include: health,metrics,info
     endpoint:
       health:
         show-details: when-authorized
         probes:
           enabled: true
   server:
     port: ${SERVER_PORT:808X}
   ```

6. **Create first Flyway migration** `src/main/resources/db/migration/V001__init.sql`:
   ```sql
   -- V001: initial schema for <name> service
   -- All tables must have tenant_id, uuid PK, TIMESTAMPTZ timestamps, deleted_at for soft deletes
   ```

7. **Create `GlobalExceptionHandler.java`** in `config/` or `api/`:
   ```java
   @RestControllerAdvice
   public class GlobalExceptionHandler {
       @ExceptionHandler(Exception.class)
       public ResponseEntity<ErrorResponse> handleAll(Exception ex, HttpServletRequest req) {
           String traceId = Span.current().getSpanContext().getTraceId();
           return ResponseEntity.status(500)
               .header("X-Trace-Id", traceId)
               .body(new ErrorResponse(new ErrorDetail("INTERNAL_ERROR", ex.getMessage(), null), traceId));
       }
   }
   ```

8. **Create multi-stage `Dockerfile`** in `infra/docker/<name>/Dockerfile`:
   ```dockerfile
   FROM eclipse-temurin:21-jdk-jammy AS builder
   WORKDIR /build
   COPY gradle/ gradle/
   COPY gradlew settings.gradle.kts build.gradle.kts ./
   COPY shared/ shared/
   COPY services/<name>/ services/<name>/
   RUN --mount=type=cache,target=/root/.gradle ./gradlew :services:<name>:bootJar -x test

   FROM eclipse-temurin:21-jre-jammy AS runtime
   RUN useradd -m -u 1000 appuser
   WORKDIR /app
   COPY --from=builder --chown=appuser:appuser /build/services/<name>/build/libs/*.jar app.jar
   USER appuser
   EXPOSE 808X
   HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
     CMD curl -f http://localhost:808X/actuator/health/live || exit 1
   ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -XX:MaxRAMPercentage=75 -jar app.jar"]
   ```

9. **Register the module** in the root `settings.gradle.kts`:
   - Add `include("services:<name>")`

10. **Verify rules checklist before finishing:**
    - [ ] No JPA dependency anywhere
    - [ ] `shared:common` not modified to add Spring deps
    - [ ] All future domain tables will need `tenant_id UUID NOT NULL`
    - [ ] Dockerfile uses multi-stage, non-root user, `MaxRAMPercentage=75`
    - [ ] First migration file exists
    - [ ] No `.proto` files defined inside this service — all contracts belong in `shared:contracts`
    - [ ] gRPC server implementations are in `infrastructure/grpc/`, annotated `@GrpcService`
    - [ ] gRPC clients are in `infrastructure/grpc/`, inject channel via `@GrpcClient`
