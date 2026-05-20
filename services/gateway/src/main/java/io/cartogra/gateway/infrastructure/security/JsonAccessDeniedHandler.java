package io.cartogra.gateway.infrastructure.security;

import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final TraceContext traceContext;

    public JsonAccessDeniedHandler(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException ex) throws IOException {
        String traceId = traceContext.currentTraceId();
        String body = """
            {"error":{"code":"ACCESS_DENIED","message":"Insufficient permissions"},"traceId":"%s"}
            """.formatted(traceId).strip();
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("X-Trace-Id", traceId);
        response.getWriter().write(body);
    }
}
