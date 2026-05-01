package io.cartogra.gateway.api;

import io.cartogra.common.api.ApiResponse;
import io.opentelemetry.api.trace.Span;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class PingController {

    @GetMapping("/ping")
    public Mono<ResponseEntity<ApiResponse<String>>> ping() {
        String traceId = Span.current().getSpanContext().getTraceId();
        return Mono.just(ResponseEntity.ok()
                .header("X-Trace-Id", traceId)
                .body(new ApiResponse<>("pong", traceId)));
    }
}
