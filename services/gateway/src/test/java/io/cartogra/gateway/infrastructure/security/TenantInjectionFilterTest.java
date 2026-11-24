package io.cartogra.gateway.infrastructure.security;

import io.cartogra.gateway.config.TenantInjectionFilter;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.web.security.InternalTokenCodec;
import io.cartogra.web.security.InternalTokens;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
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

    private final InternalTokenCodec codec = InternalTokens.codec(Clock.systemUTC());

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        filter = new TenantInjectionFilter(codec);
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedRequestInjectsHeadersFromJwt() throws Exception {
        JwtClaims claims = new JwtClaims(userId, tenantId, "user@test.com", null, "local", List.of("VIEWER"), null);
        JwtAuthentication auth = new JwtAuthentication(claims);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/registry/services");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<jakarta.servlet.ServletRequest> capturedRequest = new AtomicReference<>();

        doAnswer(inv -> { capturedRequest.set(inv.getArgument(0)); return null; })
            .when(filterChain).doFilter(any(), any());

        filter.doFilter(request, response, filterChain);

        var captured = (jakarta.servlet.http.HttpServletRequest) capturedRequest.get();
        assertThat(captured.getHeader("X-Tenant-Id")).isEqualTo(tenantId.toString());
        assertThat(captured.getHeader("X-User-Id")).isEqualTo(userId.toString());
        var token = codec.verify(captured.getHeader(InternalTokenCodec.HEADER), "registry").orElseThrow();
        assertThat(token.tenantId()).isEqualTo(tenantId);
        assertThat(token.subject()).isEqualTo(userId.toString());
        assertThat(token.roles()).containsExactly("VIEWER");
    }

    @Test
    void tokenAudienceFollowsTheRoute() {
        assertThat(TenantInjectionFilter.audienceOf("/api/v1/contracts/x")).contains("contract");
        assertThat(TenantInjectionFilter.audienceOf("/api/v1/ingestion/webhooks/github")).contains("ingestion");
        assertThat(TenantInjectionFilter.audienceOf("/api/auth/userinfo")).isEmpty();
        assertThat(TenantInjectionFilter.audienceOf("/api/v1/contracts")).contains("contract");
        assertThat(TenantInjectionFilter.audienceOf("/api/v1/contractsx")).isEmpty();
    }

    @Test
    void unauthenticatedRequestHasNoInjectedHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/registry/services");
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

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/registry/services");
        request.addHeader("X-Tenant-Id", attackerTenantId.toString());
        request.addHeader("X-User-Id", UUID.randomUUID().toString());
        request.addHeader(InternalTokenCodec.HEADER, InternalTokens.user("registry", attackerTenantId, "ADMIN"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<jakarta.servlet.ServletRequest> capturedRequest = new AtomicReference<>();

        doAnswer(inv -> { capturedRequest.set(inv.getArgument(0)); return null; })
            .when(filterChain).doFilter(any(), any());

        filter.doFilter(request, response, filterChain);

        var captured = (jakarta.servlet.http.HttpServletRequest) capturedRequest.get();
        assertThat(captured.getHeader("X-Tenant-Id")).isNull();
        assertThat(captured.getHeader("X-User-Id")).isNull();
        assertThat(captured.getHeader(InternalTokenCodec.HEADER)).isNull();
        assertThat(java.util.Collections.list(captured.getHeaderNames())).doesNotContain(InternalTokenCodec.HEADER);
    }

    @Test
    void authenticatedRequestStripsForgedHeadersAndInjectsJwtValues() throws Exception {
        UUID attackerTenantId = UUID.randomUUID();
        JwtClaims claims = new JwtClaims(userId, tenantId, "user@test.com", null, "local", List.of("VIEWER"), null);
        JwtAuthentication auth = new JwtAuthentication(claims);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/registry/services");
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

    @Test
    void apiKeyRequestSignsTheKeySubjectAndRoleAndStripsTheKeyHeader() throws Exception {
        var key = new io.cartogra.gateway.domain.ApiKey(UUID.randomUUID(), tenantId, java.util.Set.of("ci:check"));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ApiKeyAuthentication(key));
        SecurityContextHolder.setContext(context);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/contracts/check");
        request.addHeader("X-Cartogra-Api-Key", "cgk_secret");
        request.addHeader("X-User-Id", UUID.randomUUID().toString());
        AtomicReference<jakarta.servlet.ServletRequest> capturedRequest = new AtomicReference<>();
        doAnswer(inv -> { capturedRequest.set(inv.getArgument(0)); return null; })
            .when(filterChain).doFilter(any(), any());

        filter.doFilter(request, new MockHttpServletResponse(), filterChain);

        var captured = (jakarta.servlet.http.HttpServletRequest) capturedRequest.get();
        assertThat(captured.getHeader("X-Cartogra-Api-Key")).isNull();
        assertThat(captured.getHeader("X-User-Id")).isNull();
        assertThat(captured.getHeader("X-Tenant-Id")).isEqualTo(tenantId.toString());
        var token = codec.verify(captured.getHeader(InternalTokenCodec.HEADER), "contract").orElseThrow();
        assertThat(token.tenantId()).isEqualTo(tenantId);
        assertThat(token.subject()).isEqualTo("apikey:" + key.id());
        assertThat(token.roles()).containsExactly("CI_CHECK");
    }
}
