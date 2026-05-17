package io.cartogra.gateway.infrastructure.security;

import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TenantInjectionFilterTest {

    private TenantInjectionGlobalFilter filter;

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        filter = new TenantInjectionGlobalFilter();
    }

    @Test
    void authenticatedRequestInjectsHeadersFromJwt() {
        JwtClaims claims = new JwtClaims(userId, tenantId, "user@test.com", List.of("VIEWER"), null);
        JwtAuthentication auth = new JwtAuthentication(claims);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/services").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        var chain = (org.springframework.cloud.gateway.filter.GatewayFilterChain) ex -> {
            captured.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
            .block();

        assertThat(captured.get()).isNotNull();
        HttpHeaders headers = captured.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-Tenant-Id")).isEqualTo(tenantId.toString());
        assertThat(headers.getFirst("X-User-Id")).isEqualTo(userId.toString());
    }

    @Test
    void unauthenticatedRequestHasNoInjectedHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/services").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        var chain = (org.springframework.cloud.gateway.filter.GatewayFilterChain) ex -> {
            captured.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(captured.get()).isNotNull();
        HttpHeaders headers = captured.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-Tenant-Id")).isNull();
        assertThat(headers.getFirst("X-User-Id")).isNull();
    }

    @Test
    void inboundForgedHeadersAreStripped() {
        UUID attackerTenantId = UUID.randomUUID();

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/services")
            .header("X-Tenant-Id", attackerTenantId.toString())
            .header("X-User-Id", UUID.randomUUID().toString())
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        var chain = (org.springframework.cloud.gateway.filter.GatewayFilterChain) ex -> {
            captured.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        HttpHeaders headers = captured.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-Tenant-Id")).isNull();
        assertThat(headers.getFirst("X-User-Id")).isNull();
    }

    @Test
    void authenticatedRequestStripsForgedHeadersAndInjectsJwtValues() {
        UUID attackerTenantId = UUID.randomUUID();
        JwtClaims claims = new JwtClaims(userId, tenantId, "user@test.com", List.of("VIEWER"), null);
        JwtAuthentication auth = new JwtAuthentication(claims);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/services")
            .header("X-Tenant-Id", attackerTenantId.toString())
            .header("X-User-Id", UUID.randomUUID().toString())
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        var chain = (org.springframework.cloud.gateway.filter.GatewayFilterChain) ex -> {
            captured.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
            .block();

        HttpHeaders headers = captured.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-Tenant-Id")).isEqualTo(tenantId.toString());
        assertThat(headers.getFirst("X-Tenant-Id")).isNotEqualTo(attackerTenantId.toString());
        assertThat(headers.getFirst("X-User-Id")).isEqualTo(userId.toString());
    }
}
