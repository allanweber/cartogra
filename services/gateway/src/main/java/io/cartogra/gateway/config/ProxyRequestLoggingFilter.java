package io.cartogra.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ProxyRequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ProxyRequestLoggingFilter.class);

    @Override
    public int getOrder() {
        return -99;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        if (log.isDebugEnabled()) {
            log.debug("Proxying {} {} | headers={}",
                req.getMethod(),
                req.getURI(),
                req.getHeaders().toSingleValueMap());
        } else {
            log.info("Proxying {} {} | X-Tenant-Id={} X-User-Id={}",
                req.getMethod(),
                req.getURI(),
                req.getHeaders().getFirst("X-Tenant-Id"),
                req.getHeaders().getFirst("X-User-Id"));
        }
        return chain.filter(exchange);
    }
}
