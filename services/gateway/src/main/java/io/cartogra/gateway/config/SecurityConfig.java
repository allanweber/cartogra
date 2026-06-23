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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final TraceContext traceContext;
    private final CorsProperties corsProperties;

    public SecurityConfig(TraceContext traceContext, CorsProperties corsProperties) {
        this.traceContext = traceContext;
        this.corsProperties = corsProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Trace-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            RateLimitFilter rateLimitFilter,
            TenantInjectionFilter tenantFilter,
            ProxyRequestLoggingFilter proxyLoggingFilter) throws Exception {
        return http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)
            .addFilterAfter(tenantFilter, RateLimitFilter.class)
            .addFilterAfter(proxyLoggingFilter, TenantInjectionFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/register", "/api/auth/verify",
                                 "/api/auth/resend-verification",
                                 "/api/auth/login", "/api/auth/refresh",
                                 "/api/auth/logout", "/api/auth/oauth/**",
                                 "/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/api/v1/ingestion/webhooks/**").permitAll()
                .requestMatchers("/api/auth/userinfo").authenticated()
                .requestMatchers("/api/auth/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/**").hasAnyRole("VIEWER", "MEMBER", "ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint(new JsonAuthenticationEntryPoint(traceContext))
                .accessDeniedHandler(new JsonAccessDeniedHandler(traceContext)))
            .build();
    }
}
