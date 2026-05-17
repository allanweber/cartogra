package io.cartogra.gateway.config;

import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import io.cartogra.gateway.infrastructure.security.JwtAuthenticationWebFilter;
import io.cartogra.gateway.infrastructure.security.JsonAuthenticationEntryPoint;
import io.cartogra.gateway.infrastructure.security.JsonAccessDeniedHandler;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final TraceContext traceContext;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider, TraceContext traceContext) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.traceContext = traceContext;
    }

    @Bean
    public JwtAuthenticationWebFilter jwtAuthFilter() {
        return new JwtAuthenticationWebFilter(jwtTokenProvider);
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .authorizeExchange(ex -> ex
                .pathMatchers("/v1/auth/userinfo").authenticated()
                .pathMatchers("/v1/auth/admin/**").hasRole("ADMIN")
                .pathMatchers("/v1/auth/**").permitAll()
                .pathMatchers("/actuator/health/**").permitAll()
                .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .pathMatchers("/api/v1/**").hasAnyRole("VIEWER", "MEMBER", "ADMIN")
                .anyExchange().authenticated()
            )
            .addFilterBefore(jwtAuthFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new JsonAuthenticationEntryPoint(traceContext))
                .accessDeniedHandler(new JsonAccessDeniedHandler(traceContext))
            )
            .build();
    }

}
