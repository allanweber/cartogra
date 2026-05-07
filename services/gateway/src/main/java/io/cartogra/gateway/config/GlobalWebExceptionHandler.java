package io.cartogra.gateway.config;

import io.cartogra.common.api.ApiError;
import io.cartogra.common.api.ApiErrorResponse;
import io.cartogra.common.api.ErrorCodes;
import io.opentelemetry.api.trace.Span;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalWebExceptionHandler implements WebExceptionHandler {

    private final ObjectMapper objectMapper;

    public GlobalWebExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status = ex instanceof ResponseStatusException rse
                ? HttpStatus.valueOf(rse.getStatusCode().value())
                : HttpStatus.INTERNAL_SERVER_ERROR;
        String code = status == HttpStatus.INTERNAL_SERVER_ERROR
                ? ErrorCodes.INTERNAL_ERROR
                : ErrorCodes.BAD_REQUEST;
        String message = status == HttpStatus.INTERNAL_SERVER_ERROR
                ? "An unexpected error occurred"
                : ex.getMessage();
        String traceId = Span.current().getSpanContext().getTraceId();

        ApiErrorResponse body = new ApiErrorResponse(ApiError.of(code, message), traceId);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().set("X-Trace-Id", traceId);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] bytes = objectMapper.writeValueAsBytes(body);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
