package io.cartogra.web.security;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Writes the standard 403 error envelope for requests the filter chain rejects. Used as the entry point too:
 * downstream services never authenticate anyone themselves, so a missing role is always a 403, not a 401.
 */
public class JsonForbiddenHandler implements AccessDeniedHandler, AuthenticationEntryPoint {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        write(response);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        write(response);
    }

    private static void write(HttpServletResponse response) throws IOException {
        String traceId = Span.current().getSpanContext().getTraceId();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("X-Trace-Id", traceId);
        response.getWriter().write("""
            {"error":{"code":"FORBIDDEN","message":"Insufficient permissions"},"traceId":"%s"}"""
            .formatted(traceId));
    }
}
