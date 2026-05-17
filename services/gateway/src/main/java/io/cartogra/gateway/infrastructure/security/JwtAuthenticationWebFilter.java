package io.cartogra.gateway.infrastructure.security;

import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public class JwtAuthenticationWebFilter implements WebFilter {

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationWebFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // switchIfEmpty fires whenever the upstream Mono<Void> completes with no onNext signal,
        // which Mono<Void> always does — causing chain.filter() to be called twice.
        // Fix: produce a non-void signal from the authenticated branch so switchIfEmpty only
        // triggers when no token was present at all.
        return extractRawToken(exchange)
            .flatMap(jwtTokenProvider::decode)
            .onErrorResume(JwtException.class, __ -> Mono.empty())
            .flatMap(claims -> chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthentication(claims)))
                .thenReturn(Boolean.TRUE)
            )
            .switchIfEmpty(Mono.defer(() -> chain.filter(exchange).thenReturn(Boolean.FALSE)))
            .then();
    }

    private Mono<String> extractRawToken(ServerWebExchange exchange) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst("jwt");
        if (cookie != null) {
            return Mono.just(cookie.getValue());
        }
        String bearer = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return Mono.just(bearer.substring(7));
        }
        return Mono.empty();
    }
}
