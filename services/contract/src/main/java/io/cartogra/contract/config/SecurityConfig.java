package io.cartogra.contract.config;

import io.cartogra.common.identity.Actor;
import io.cartogra.common.identity.Roles;
import io.cartogra.web.filter.InternalTokenFilter;
import io.cartogra.web.security.InternalTokenCodec;
import io.cartogra.web.security.InternalTokenProperties;
import io.cartogra.web.security.JsonForbiddenHandler;
import io.cartogra.web.security.JsonUnauthorizedEntryPoint;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
@EnableConfigurationProperties(InternalTokenProperties.class)
public class SecurityConfig {

    public static final String AUDIENCE = "contract";

    @Bean
    public InternalTokenCodec internalTokenCodec(InternalTokenProperties properties) {
        return new InternalTokenCodec(properties, Clock.systemUTC());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, InternalTokenCodec codec) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .anonymous(a -> a.principal(Actor.ANONYMOUS))
            .addFilterBefore(new InternalTokenFilter(codec, AUDIENCE), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/internal/**").hasRole(Roles.SERVICE)
                .requestMatchers(HttpMethod.POST, "/check", "/check/").hasRole("CI_CHECK")
                .requestMatchers(HttpMethod.GET, "/**").authenticated()
                .requestMatchers(HttpMethod.HEAD, "/**").authenticated()
                .requestMatchers(HttpMethod.OPTIONS, "/**").authenticated()
                .anyRequest().hasAnyRole("ADMIN", "MEMBER", "TEAM_OWNER"))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(new JsonUnauthorizedEntryPoint())
                .accessDeniedHandler(new JsonForbiddenHandler()))
            .build();
    }
}
