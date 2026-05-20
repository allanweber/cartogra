package io.cartogra.gateway.infrastructure.security;

import io.cartogra.gateway.config.TenantInjectionFilter;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class TenantInjectionFilterTest {

    @Mock
    private FilterChain filterChain;

    private TenantInjectionFilter filter;

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        filter = new TenantInjectionFilter();
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedRequestInjectsHeadersFromJwt() throws Exception {
        JwtClaims claims = new JwtClaims(userId, tenantId, "user@test.com", List.of("VIEWER"), null);
        JwtAuthentication auth = new JwtAuthentication(claims);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/services");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<jakarta.servlet.ServletRequest> capturedRequest = new AtomicReference<>();

        doAnswer(inv -> { capturedRequest.set(inv.getArgument(0)); return null; })
            .when(filterChain).doFilter(any(), any());

        filter.doFilter(request, response, filterChain);

        var captured = (jakarta.servlet.http.HttpServletRequest) capturedRequest.get();
        assertThat(captured.getHeader("X-Tenant-Id")).isEqualTo(tenantId.toString());
        assertThat(captured.getHeader("X-User-Id")).isEqualTo(userId.toString());
    }

    @Test
    void unauthenticatedRequestHasNoInjectedHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/services");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<jakarta.servlet.ServletRequest> capturedRequest = new AtomicReference<>();

        doAnswer(inv -> { capturedRequest.set(inv.getArgument(0)); return null; })
            .when(filterChain).doFilter(any(), any());

        filter.doFilter(request, response, filterChain);

        var captured = (jakarta.servlet.http.HttpServletRequest) capturedRequest.get();
        assertThat(captured.getHeader("X-Tenant-Id")).isNull();
        assertThat(captured.getHeader("X-User-Id")).isNull();
    }

    @Test
    void inboundForgedHeadersAreStripped() throws Exception {
        UUID attackerTenantId = UUID.randomUUID();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/services");
        request.addHeader("X-Tenant-Id", attackerTenantId.toString());
        request.addHeader("X-User-Id", UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<jakarta.servlet.ServletRequest> capturedRequest = new AtomicReference<>();

        doAnswer(inv -> { capturedRequest.set(inv.getArgument(0)); return null; })
            .when(filterChain).doFilter(any(), any());

        filter.doFilter(request, response, filterChain);

        var captured = (jakarta.servlet.http.HttpServletRequest) capturedRequest.get();
        assertThat(captured.getHeader("X-Tenant-Id")).isNull();
        assertThat(captured.getHeader("X-User-Id")).isNull();
    }

    @Test
    void authenticatedRequestStripsForgedHeadersAndInjectsJwtValues() throws Exception {
        UUID attackerTenantId = UUID.randomUUID();
        JwtClaims claims = new JwtClaims(userId, tenantId, "user@test.com", List.of("VIEWER"), null);
        JwtAuthentication auth = new JwtAuthentication(claims);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/services");
        request.addHeader("X-Tenant-Id", attackerTenantId.toString());
        request.addHeader("X-User-Id", UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<jakarta.servlet.ServletRequest> capturedRequest = new AtomicReference<>();

        doAnswer(inv -> { capturedRequest.set(inv.getArgument(0)); return null; })
            .when(filterChain).doFilter(any(), any());

        filter.doFilter(request, response, filterChain);

        var captured = (jakarta.servlet.http.HttpServletRequest) capturedRequest.get();
        assertThat(captured.getHeader("X-Tenant-Id")).isEqualTo(tenantId.toString());
        assertThat(captured.getHeader("X-Tenant-Id")).isNotEqualTo(attackerTenantId.toString());
        assertThat(captured.getHeader("X-User-Id")).isEqualTo(userId.toString());
    }
}
