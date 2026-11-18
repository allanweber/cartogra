package io.cartogra.web.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.cartogra.common.identity.Actor;
import io.cartogra.web.security.InternalClaims;
import io.cartogra.web.security.InternalTokenCodec;
import io.cartogra.web.security.InternalTokenProperties;
import io.cartogra.web.security.InternalTokens;
import io.cartogra.web.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class InternalTokenFilterTest {

    private static final String AUDIENCE = "registry";
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private final InternalTokenFilter filter = new InternalTokenFilter(InternalTokens.codec(Clock.systemUTC()), AUDIENCE);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final FilterChain chain = mock(FilterChain.class);

    @BeforeEach
    void stubWriter() throws Exception {
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenSeedsActorAndRoles() throws Exception {
        when(request.getHeader(InternalTokens.HEADER)).thenReturn(InternalTokens.user(AUDIENCE, TENANT, USER_ID, "ADMIN,VIEWER"));
        when(request.getHeader("X-Tenant-Id")).thenReturn(TENANT.toString());

        filter.doFilter(request, response, chain);

        var auth = (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isEqualTo(Actor.admin(USER_ID));
        assertThat(auth.getAuthorities())
                .containsExactlyInAnyOrder(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_VIEWER"));
        verify(chain).doFilter(request, response);
    }

    @Test
    void serviceTokenHasNoUserAndIsNotAdmin() throws Exception {
        when(request.getHeader(InternalTokens.HEADER)).thenReturn(InternalTokens.service(AUDIENCE, TENANT));

        filter.doFilter(request, response, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isEqualTo(Actor.ANONYMOUS);
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_SERVICE");
        verify(chain).doFilter(request, response);
    }

    @Test
    void missingTokenContinuesUnauthenticated() throws Exception {
        when(request.getHeader("X-User-Roles")).thenReturn("ADMIN");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(5)), ZoneOffset.UTC);
        String token = InternalTokens.codec(past).sign(claims(), AUDIENCE);
        assertRejected(token);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        var other = new InternalTokenCodec(
                new InternalTokenProperties("another-internal-key-0a1b2c3d4e5f6a7b8c9d", Duration.ofSeconds(60)),
                Clock.systemUTC());
        assertRejected(other.sign(claims(), AUDIENCE));
    }

    @Test
    void tokenForAnotherAudienceIsRejected() throws Exception {
        assertRejected(InternalTokens.user("topology", TENANT, USER_ID, "ADMIN"));
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        assertRejected("not-a-jwt");
    }

    @Test
    void tenantHeaderThatDiffersFromTokenTenantIsRejected() throws Exception {
        when(request.getHeader("X-Tenant-Id")).thenReturn(UUID.randomUUID().toString());
        assertRejected(InternalTokens.user(AUDIENCE, TENANT, USER_ID, "ADMIN"));
    }

    private void assertRejected(String token) throws Exception {
        when(request.getHeader(InternalTokens.HEADER)).thenReturn(token);

        filter.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static InternalClaims claims() {
        return new InternalClaims(TENANT, USER_ID.toString(), List.of("ADMIN"));
    }

    @Test
    void validTokenBindsTheTokenTenantForTheChain() throws Exception {
        when(request.getHeader(InternalTokens.HEADER)).thenReturn(InternalTokens.service(AUDIENCE, TENANT));
        AtomicReference<Optional<UUID>> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(TenantContext.current()));

        assertThat(seen.get()).contains(TENANT);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void missingTokenBindsNoTenant() throws Exception {
        when(request.getHeader("X-Tenant-Id")).thenReturn(TENANT.toString());
        AtomicReference<Optional<UUID>> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(TenantContext.current()));

        assertThat(seen.get()).isEmpty();
    }
}
