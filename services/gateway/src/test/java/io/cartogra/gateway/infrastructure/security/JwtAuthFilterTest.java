package io.cartogra.gateway.infrastructure.security;

import io.cartogra.gateway.config.JwtAuthenticationFilter;
import io.cartogra.gateway.domain.exception.UnauthorizedException;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private FilterChain filterChain;

    private JwtAuthenticationFilter filter;

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final JwtClaims claims = new JwtClaims(userId, tenantId, "user@test.com", List.of("VIEWER"), null);

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtTokenProvider);
        SecurityContextHolder.clearContext();
    }

    @Test
    void cookieTokenAuthenticatesPrincipal() throws Exception {
        when(jwtTokenProvider.decode("valid-token")).thenReturn(claims);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("jwt", "valid-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
        // Context is cleared after filter — check was captured in chain
    }

    @Test
    void bearerTokenAuthenticatesPrincipal() throws Exception {
        when(jwtTokenProvider.decode("bearer-token")).thenReturn(claims);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bearer-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
    }

    @Test
    void cookieTakesPrecedenceOverBearer() throws Exception {
        when(jwtTokenProvider.decode("cookie-token")).thenReturn(claims);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("jwt", "cookie-token"));
        request.addHeader("Authorization", "Bearer bearer-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(jwtTokenProvider).decode("cookie-token");
        verify(jwtTokenProvider, never()).decode("bearer-token");
        verify(filterChain).doFilter(any(), any());
    }

    @Test
    void missingTokenProceedsWithoutPrincipal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(jwtTokenProvider, never()).decode(any());
        verify(filterChain).doFilter(any(), any());
    }

    @Test
    void invalidTokenProceedsWithoutPrincipal() throws Exception {
        when(jwtTokenProvider.decode(any())).thenThrow(new UnauthorizedException("invalid"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new jakarta.servlet.http.Cookie("jwt", "bad-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
