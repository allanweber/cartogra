package io.cartogra.gateway.config;

import io.cartogra.gateway.infrastructure.security.JsonAccessDeniedHandler;
import io.cartogra.gateway.infrastructure.security.JsonAuthenticationEntryPoint;
import io.cartogra.gateway.infrastructure.tracing.TraceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final TraceContext traceContext;

    public SecurityConfig(TraceContext traceContext) {
        this.traceContext = traceContext;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            RateLimitFilter rateLimitFilter,
            TenantInjectionFilter tenantFilter,
            ProxyRequestLoggingFilter proxyLoggingFilter) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)
            .addFilterAfter(tenantFilter, RateLimitFilter.class)
            .addFilterAfter(proxyLoggingFilter, TenantInjectionFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/v1/auth/register", "/v1/auth/verify",
                                 "/v1/auth/login", "/v1/auth/refresh",
                                 "/v1/auth/logout", "/v1/auth/oauth/**").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/v1/auth/userinfo").authenticated()
                .requestMatchers("/v1/auth/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/**").hasAnyRole("VIEWER", "MEMBER", "ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint(new JsonAuthenticationEntryPoint(traceContext))
                .accessDeniedHandler(new JsonAccessDeniedHandler(traceContext)))
            .build();
    }
}
