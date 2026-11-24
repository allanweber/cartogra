package io.cartogra.gateway.infrastructure.security;

import io.cartogra.gateway.config.ApiKeyAllowlist;
import io.cartogra.gateway.config.ApiKeyAuthenticationFilter;
import io.cartogra.gateway.config.RateLimiter;
import io.cartogra.gateway.domain.ApiKey;
import io.cartogra.gateway.domain.ApiKeyAuthenticator;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

    private static final String CHECK = "/api/v1/contracts/check";

    @Mock
    private ApiKeyAuthenticator authenticator;
    @Mock
    private FilterChain chain;
    @Mock
    private RateLimiter rateLimit;

    private ApiKeyAuthenticationFilter filter;
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        TraceContext trace = mock(TraceContext.class);
        org.mockito.Mockito.lenient().when(trace.currentTraceId()).thenReturn("trace");
        filter = new ApiKeyAuthenticationFilter(authenticator, trace, rateLimit);
        org.mockito.Mockito.lenient().when(rateLimit.allowBeforeKeyLookup(any(), any())).thenReturn(true);
        SecurityContextHolder.clearContext();
    }

    private void withJwt(UUID tenant) {
        var claims = new JwtClaims(UUID.randomUUID(), tenant, "u@test.com", null, "local", List.of("ADMIN"), null);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthentication(claims));
        SecurityContextHolder.setContext(context);
    }

    private MockHttpServletRequest check(String key) {
        var request = new MockHttpServletRequest("POST", CHECK);
        if (key != null) {
            request.addHeader("X-Cartogra-Api-Key", key);
        }
        return request;
    }

    @Test
    void offTheAllowlistTheHeaderIsIgnoredAndTheJwtStays() throws Exception {
        withJwt(tenantA);
        var request = new MockHttpServletRequest("GET", "/api/v1/registry/services");
        request.addHeader("X-Cartogra-Api-Key", "cgk_x");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
        verify(authenticator, never()).authenticate(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isInstanceOf(JwtAuthentication.class);
    }

    @Test
    void getOnTheCheckPathIsNotAllowlisted() throws Exception {
        var request = new MockHttpServletRequest("GET", CHECK);
        request.addHeader("X-Cartogra-Api-Key", "cgk_x");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        verify(authenticator, never()).authenticate(any());
    }

    @Test
    void jwtWithNoKeyGets403() throws Exception {
        withJwt(tenantA);
        var response = new MockHttpServletResponse();

        filter.doFilter(check(null), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void noKeyAndNoJwtGets401() throws Exception {
        var response = new MockHttpServletResponse();

        filter.doFilter(check(null), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void unknownRevokedOrExpiredKeyGets401EvenWithAJwt() throws Exception {
        withJwt(tenantA);
        when(authenticator.authenticate("cgk_bad")).thenReturn(Optional.empty());
        var response = new MockHttpServletResponse();

        filter.doFilter(check("cgk_bad"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void keyWithoutTheScopeGets403() throws Exception {
        when(authenticator.authenticate("cgk_other"))
                .thenReturn(Optional.of(new ApiKey(UUID.randomUUID(), tenantA, Set.of("spans:write"))));
        var response = new MockHttpServletResponse();

        filter.doFilter(check("cgk_other"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void activeKeyReplacesTheJwtOfAnotherTenant() throws Exception {
        withJwt(tenantB);
        var keyId = UUID.randomUUID();
        when(authenticator.authenticate("cgk_ok"))
                .thenReturn(Optional.of(new ApiKey(keyId, tenantA, Set.of("ci:check"))));
        AtomicReference<Authentication> seen = new AtomicReference<>();
        doAnswer(inv -> {
            seen.set(SecurityContextHolder.getContext().getAuthentication());
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(check("cgk_ok"), new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isInstanceOfSatisfying(ApiKeyAuthentication.class, auth -> {
            assertThat(auth.getTenantId()).isEqualTo(tenantA);
            assertThat(auth.subject()).isEqualTo("apikey:" + keyId);
            assertThat(auth.getRoles()).containsExactly("CI_CHECK");
        });
    }

    @Test
    void ipBucketIsChargedBeforeTheKeyLookupAndAnExhaustedBucketStopsTheLookup() throws Exception {
        when(rateLimit.allowBeforeKeyLookup(any(), any())).thenReturn(false);
        var response = new MockHttpServletResponse();

        filter.doFilter(check("cgk_bad"), response, chain);

        verify(authenticator, never()).authenticate(any());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void ipBucketIsNotChargedOffTheAllowlist() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/registry/services");
        request.addHeader("X-Cartogra-Api-Key", "cgk_x");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(rateLimit, never()).allowBeforeKeyLookup(any(), any());
    }

    @Test
    void oneTrailingSlashIsOnTheAllowlist() {
        assertThat(ApiKeyAllowlist.requiredScope("POST", CHECK + "/")).contains("ci:check");
        assertThat(ApiKeyAllowlist.requiredScope("POST", CHECK)).contains("ci:check");
        assertThat(ApiKeyAllowlist.requiredScope("POST", CHECK + "//")).isEmpty();
        assertThat(ApiKeyAllowlist.requiredScope("POST", CHECK + "/x")).isEmpty();
        assertThat(ApiKeyAllowlist.requiredScope("GET", CHECK + "/")).isEmpty();
    }
}
