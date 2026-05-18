package io.cartogra.gateway.config;

import io.cartogra.common.api.ApiError;
import io.cartogra.common.api.ApiErrorResponse;
import io.cartogra.common.api.ErrorCodes;
import io.cartogra.gateway.domain.exception.ConflictException;
import io.cartogra.gateway.domain.exception.InvalidOAuthStateException;
import io.cartogra.gateway.domain.exception.InvalidOtpException;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.domain.exception.UnverifiedEmailException;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(GlobalWebExceptionHandler.class);

    private final ObjectMapper objectMapper;
    private final TraceContext traceContext;

    public GlobalWebExceptionHandler(ObjectMapper objectMapper, TraceContext traceContext) {
        this.objectMapper = objectMapper;
        this.traceContext = traceContext;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status;
        String code;
        String message;

        if (ex instanceof UnauthorizedException || ex instanceof UnverifiedEmailException) {
            status = HttpStatus.UNAUTHORIZED;
            code = ErrorCodes.UNAUTHORIZED;
            message = ex.getMessage();
            log.warn("Unauthorized: {}", ex.getMessage());
        } else if (ex instanceof ConflictException) {
            status = HttpStatus.CONFLICT;
            code = ErrorCodes.CONFLICT;
            message = ex.getMessage();
            log.warn("Conflict: {}", ex.getMessage());
        } else if (ex instanceof InvalidOtpException || ex instanceof InvalidOAuthStateException
                || ex instanceof IllegalArgumentException) {
            status = HttpStatus.BAD_REQUEST;
            code = ErrorCodes.BAD_REQUEST;
            message = ex.getMessage();
            log.warn("Bad request: {}", ex.getMessage());
        } else if (ex instanceof ResponseStatusException rse) {
            status = HttpStatus.valueOf(rse.getStatusCode().value());
            code = status == HttpStatus.UNAUTHORIZED ? ErrorCodes.UNAUTHORIZED : ErrorCodes.BAD_REQUEST;
            message = rse.getReason() != null ? rse.getReason() : ex.getMessage();
            log.warn("Response status exception [{}]: {}", status.value(), message);
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            code = ErrorCodes.INTERNAL_ERROR;
            message = "An unexpected error occurred";
            log.error("Unhandled exception on {} {}", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), ex);
        }

        if (exchange.getResponse().isCommitted()) {
            log.warn("Response already committed, cannot write error for {} {}",
                exchange.getRequest().getMethod(), exchange.getRequest().getPath());
            return Mono.empty();
        }

        return Mono.deferContextual(ctx -> {
            String traceId = traceContext.currentTraceId(ctx);
            ApiErrorResponse body = new ApiErrorResponse(ApiError.of(code, message), traceId);
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().set("X-Trace-Id", traceId);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        });
    }
}
