You are working in the Cartogra monorepo. Read AGENTS.md for all project rules before proceeding.

Add a new REST endpoint to an existing service with full envelope response, OTel tracing, and error handling.

Arguments: $ARGUMENTS
(Expected: `<service> <HTTP-METHOD> <path> [description]` — e.g., `/add-endpoint registry GET /services/{id} get service by id`)

## Steps

1. **Parse arguments**: service, method (GET/POST/PUT/DELETE/PATCH), path, description

2. **Identify the right controller** in `services/<service>/src/main/java/io/cartogra/<service>/api/`
   - Use an existing controller if one covers this resource
   - Create a new `<Resource>Controller.java` if none exists

3. **Define request/response records** (Java 17 records) in `api/`:
   ```java
   // Request (for POST/PUT/PATCH)
   public record CreateXyzRequest(
       @NotBlank String name,
       // other fields...
   ) {}

   // Response (always a record — never a mutable POJO)
   public record XyzResponse(
       UUID id,
       String name,
       // other fields...
   ) {}
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

5. **Ensure `ApiResponse<T>` record exists** in `shared:common` or `api/`:
   ```java
   public record ApiResponse<T>(T data, String traceId) {}
   ```

6. **Add error case to `GlobalExceptionHandler`** for domain-specific exceptions:
   ```java
   @ExceptionHandler(XyzNotFoundException.class)
   public ResponseEntity<ErrorResponse> handleNotFound(XyzNotFoundException ex) {
       String traceId = Span.current().getSpanContext().getTraceId();
       return ResponseEntity.status(HttpStatus.NOT_FOUND)
           .header("X-Trace-Id", traceId)
           .body(new ErrorResponse(
               new ErrorDetail("RESOURCE_NOT_FOUND", ex.getMessage(), null),
               traceId
           ));
   }
   ```

7. **Add use case interface** in `application/`:
   ```java
   public interface XyzUseCase {
       XyzResponse findById(UUID tenantId, UUID id);
   }
   ```

8. **Add a Bruno smoke test** in `bruno/<service>/<tag>/`:
   - File name: `<NN>-<operation-id>.bru` (next seq number in the folder)
   - Use `{{baseUrl}}`, `{{authToken}}`, `{{tenantId}}` — never hardcode values
   - CI endpoints: use `headers { X-Cartogra-Api-Key: {{apiKey}} }` with `auth: none`
   - POST/PUT responses: add `script:post-response` to capture created ID into a variable
   - Every test MUST assert:
     - Correct HTTP status code
     - `res.body.data` and `res.body.traceId` present (enveloped endpoints)
     - `res.body.traceId` matches `/^[0-9a-f]{32}$/`
     - `res.headers["x-trace-id"]` equals `res.body.traceId`
   - 204 responses: assert only `res.headers["x-trace-id"]` pattern
   - Webhook/CI flat endpoints: assert `res.headers["x-trace-id"]` only (no envelope check)

   ```bru
   meta {
     name: Get Xyz
     type: http
     seq: 3
   }

   get {
     url: {{baseUrl}}/xyz/{{xyzId}}
     body: none
     auth: bearer
   }

   auth:bearer {
     token: {{authToken}}
   }

   tests {
     test("status is 200", function() {
       expect(res.status).to.equal(200);
     });

     test("response envelope present", function() {
       expect(res.body).to.have.property("data");
       expect(res.body).to.have.property("traceId");
     });

     test("traceId is 32 lowercase hex", function() {
       expect(res.body.traceId).to.match(/^[0-9a-f]{32}$/);
     });

     test("X-Trace-Id header matches body traceId", function() {
       expect(res.headers["x-trace-id"]).to.equal(res.body.traceId);
     });
   }
   ```

   Alternatively, regenerate the whole collection from the updated OpenAPI spec:

   ```bash
   node scripts/generate-bruno.mjs docs/api/<service>.openapi.yaml bruno/<service>
   ```

9. **Verify rules checklist before finishing:**
   - [ ] Response includes `{"data": ..., "traceId": "..."}` — NOT a flat object
   - [ ] `X-Trace-Id` header is set on every response
   - [ ] `traceId` is extracted from `Span.current().getSpanContext().getTraceId()` (32 hex chars)
   - [ ] Controller uses explicit constructor injection (no `@Autowired`)
   - [ ] Request/response types are records (not mutable classes)
   - [ ] `tenant_id` is extracted from the `X-Tenant-Id` header (injected by gateway)
   - [ ] Error responses also include `traceId` and `X-Trace-Id` header
   - [ ] This is NOT a webhook endpoint (those skip the envelope)
   - [ ] Bruno `.bru` file added in `bruno/<service>/<tag>/`
