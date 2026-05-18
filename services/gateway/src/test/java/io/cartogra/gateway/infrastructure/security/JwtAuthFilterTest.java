package io.cartogra.gateway.infrastructure.security;

import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private JwtAuthenticationWebFilter filter;

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final JwtClaims claims = new JwtClaims(userId, tenantId, "user@test.com", List.of("VIEWER"), null);

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationWebFilter(jwtTokenProvider);
    }

    @Test
    void cookieTokenAuthenticatesPrincipal() {
        when(jwtTokenProvider.decode("valid-token")).thenReturn(Mono.just(claims));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/test")
            .cookie(new HttpCookie("jwt", "valid-token"))
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<SecurityContext> capturedContext = new AtomicReference<>();
        WebFilterChain chain = ex -> ReactiveSecurityContextHolder.getContext()
            .doOnNext(capturedContext::set)
            .then();

        filter.filter(exchange, chain).block();

        assertThat(capturedContext.get()).isNotNull();
        assertThat(capturedContext.get().getAuthentication()).isInstanceOf(JwtAuthentication.class);
        JwtAuthentication auth = (JwtAuthentication) capturedContext.get().getAuthentication();
        assertThat(auth.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void bearerTokenAuthenticatesPrincipal() {
        when(jwtTokenProvider.decode("bearer-token")).thenReturn(Mono.just(claims));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/test")
            .header(HttpHeaders.AUTHORIZATION, "Bearer bearer-token")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<SecurityContext> capturedContext = new AtomicReference<>();
        WebFilterChain chain = ex -> ReactiveSecurityContextHolder.getContext()
            .doOnNext(capturedContext::set)
            .then();

        filter.filter(exchange, chain).block();

        assertThat(capturedContext.get()).isNotNull();
        assertThat(capturedContext.get().getAuthentication()).isInstanceOf(JwtAuthentication.class);
    }

    @Test
    void cookieTakesPrecedenceOverBearer() {
        when(jwtTokenProvider.decode("cookie-token")).thenReturn(Mono.just(claims));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/test")
            .cookie(new HttpCookie("jwt", "cookie-token"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer bearer-token")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    void missingTokenProceedsWithoutPrincipal() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    void invalidTokenProceedsWithoutPrincipal() {
        when(jwtTokenProvider.decode(any())).thenReturn(Mono.error(new BadJwtException("invalid token")));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/test")
            .cookie(new HttpCookie("jwt", "bad-token"))
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isTrue();
    }
}
