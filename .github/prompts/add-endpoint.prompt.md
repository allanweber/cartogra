---
mode: 'agent'
description: 'Add a REST endpoint with envelope response, OTel tracing, and error handling'
---

You are working in the Cartogra monorepo. The full project rules are in `.github/copilot-instructions.md` — apply them to everything you generate.

Add a new REST endpoint to an existing service with full envelope response, OTel tracing, and error handling.

**Usage:** provide `<service> <HTTP-METHOD> <path> [description]` (e.g., `registry GET /services/{id} get service by id`)

## Steps

1. **Parse arguments**: service, method (GET/POST/PUT/DELETE/PATCH), path, description

2. **Identify the right controller** in `services/<service>/src/main/java/io/cartogra/<service>/api/`
   - Use an existing controller if it covers this resource
   - Create a new `<Resource>Controller.java` if none exists

3. **Define request/response records** in `api/`:
   ```java
   public record CreateXyzRequest(@NotBlank String name) {}
   public record XyzResponse(UUID id, String name) {}
   ```

4. **Write the controller method** with envelope response:
   ```java
   @RestController
   @RequestMapping("/api/v1/<resource>")
   public class XyzController {

       private final XyzUseCase xyzUseCase;

       public XyzController(XyzUseCase xyzUseCase) {
           this.xyzUseCase = xyzUseCase;
       }

       @GetMapping("/{id}")
       public ResponseEntity<ApiResponse<XyzResponse>> getById(
               @PathVariable UUID id,
               @RequestHeader("X-Tenant-Id") UUID tenantId) {
           String traceId = Span.current().getSpanContext().getTraceId();
           XyzResponse data = xyzUseCase.findById(tenantId, id);
           return ResponseEntity.ok()
               .header("X-Trace-Id", traceId)
               .body(new ApiResponse<>(data, traceId));
       }
   }
   ```

5. **Ensure `ApiResponse<T>` record exists**:
   ```java
   public record ApiResponse<T>(T data, String traceId) {}
   ```

6. **Add error case to `GlobalExceptionHandler`**:
   ```java
   @ExceptionHandler(XyzNotFoundException.class)
   public ResponseEntity<ErrorResponse> handleNotFound(XyzNotFoundException ex) {
       String traceId = Span.current().getSpanContext().getTraceId();
       return ResponseEntity.status(HttpStatus.NOT_FOUND)
           .header("X-Trace-Id", traceId)
           .body(new ErrorResponse(
               new ErrorDetail("RESOURCE_NOT_FOUND", ex.getMessage(), null), traceId));
   }
   ```

7. **Add use case interface** in `application/`:
   ```java
   public interface XyzUseCase {
       XyzResponse findById(UUID tenantId, UUID id);
   }
   ```

8. **Verify rules checklist:**
   - [ ] Response includes `{"data": ..., "traceId": "..."}` — NOT a flat object
   - [ ] `X-Trace-Id` header set on every response
   - [ ] `traceId` extracted from `Span.current().getSpanContext().getTraceId()` (32 hex chars)
   - [ ] Constructor injection — no `@Autowired`
   - [ ] Request/response types are records (not mutable classes)
   - [ ] `tenant_id` extracted from `X-Tenant-Id` header
   - [ ] Error responses also include `traceId` and `X-Trace-Id` header
   - [ ] This is NOT a webhook endpoint (those skip the envelope)
