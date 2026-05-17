package io.cartogra.gateway.infrastructure.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class TenantInjectionGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var stripped = exchange.getRequest().mutate()
            .headers(h -> {
                h.remove("X-Tenant-Id");
                h.remove("X-User-Id");
            })
            .build();

        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .cast(JwtAuthentication.class)
            .map(auth -> stripped.mutate()
                .header("X-Tenant-Id", auth.getTenantId().toString())
                .header("X-User-Id", auth.getUserId().toString())
                .build())
            .defaultIfEmpty(stripped)
            .flatMap(req -> chain.filter(exchange.mutate().request(req).build()));
    }
}
