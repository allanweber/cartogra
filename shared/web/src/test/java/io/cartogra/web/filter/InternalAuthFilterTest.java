package io.cartogra.web.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class InternalAuthFilterTest {

    private final InternalAuthFilter filter = new InternalAuthFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void seedsAuthenticationFromRolesAndUserIdHeaders() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-User-Roles")).thenReturn("ADMIN, VIEWER");
        when(request.getHeader("X-User-Id")).thenReturn("user-123");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        var auth = (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isEqualTo("user-123");
        assertThat(auth.getAuthorities())
                .containsExactlyInAnyOrder(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_VIEWER"));
        verify(chain).doFilter(request, response);
    }

    @Test
    void leavesSecurityContextEmptyWhenRolesHeaderMissing() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-User-Roles")).thenReturn(null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void leavesSecurityContextEmptyWhenRolesHeaderBlank() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-User-Roles")).thenReturn("  ");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void alwaysContinuesFilterChainEvenWithoutHeaders() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
    }
}
