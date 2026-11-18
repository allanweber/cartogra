package io.cartogra.web.security;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/** Writes the standard 401 error envelope for a request with no valid internal token. */
public class JsonUnauthorizedEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        write(response);
    }

    public static void write(HttpServletResponse response) throws IOException {
        String traceId = Span.current().getSpanContext().getTraceId();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("X-Trace-Id", traceId);
        response.getWriter().write("""
            {"error":{"code":"UNAUTHORIZED","message":"Authentication required"},"traceId":"%s"}"""
            .formatted(traceId));
    }
}
