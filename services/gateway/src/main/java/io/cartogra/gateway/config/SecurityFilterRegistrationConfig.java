package io.cartogra.gateway.config;

import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * These filters run inside the Spring Security chain (see {@link SecurityConfig}). As {@code @Component}s Boot
 * would also register them as servlet filters; an early servlet-level run sees no authentication yet and,
 * being once-per-request, then skips its in-chain run, so rate limiting fell back to per-IP buckets.
 */
@Configuration
public class SecurityFilterRegistrationConfig {

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter f) {
        return disabled(f);
    }

    @Bean
    public FilterRegistrationBean<GuestWriteDenyFilter> guestWriteDenyFilterRegistration(GuestWriteDenyFilter f) {
        return disabled(f);
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter f) {
        return disabled(f);
    }

    @Bean
    public FilterRegistrationBean<TenantInjectionFilter> tenantFilterRegistration(TenantInjectionFilter f) {
        return disabled(f);
    }

    @Bean
    public FilterRegistrationBean<ProxyRequestLoggingFilter> proxyLoggingFilterRegistration(
            ProxyRequestLoggingFilter f) {
        return disabled(f);
    }

    private static <T extends Filter> FilterRegistrationBean<T> disabled(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
