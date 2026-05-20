package io.cartogra.gateway.infrastructure.security;

import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final TraceContext traceContext;

    public JsonAuthenticationEntryPoint(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException ex) throws IOException {
        String traceId = traceContext.currentTraceId();
        String body = """
            {"error":{"code":"UNAUTHORIZED","message":"Authentication required"},"traceId":"%s"}
            """.formatted(traceId).strip();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("X-Trace-Id", traceId);
        response.getWriter().write(body);
    }
}
