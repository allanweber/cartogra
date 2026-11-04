package io.cartogra.topology.config;

import io.cartogra.common.identity.Actor;
import jakarta.servlet.DispatcherType;
import io.cartogra.web.filter.InternalAuthFilter;
import io.cartogra.web.security.JsonForbiddenHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
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

    @Bean
    public InternalAuthFilter internalAuthFilter() {
        return new InternalAuthFilter();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, InternalAuthFilter internalAuthFilter) throws Exception {
        JsonForbiddenHandler forbidden = new JsonForbiddenHandler();
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .anonymous(a -> a.principal(Actor.ANONYMOUS))
            .addFilterBefore(internalAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/internal/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/**").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().hasAnyRole("ADMIN", "MEMBER", "TEAM_OWNER"))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(forbidden)
                .accessDeniedHandler(forbidden))
            .build();
    }
}
