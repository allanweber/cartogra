package io.cartogra.gateway.api;

import io.cartogra.gateway.AbstractGatewayIT;
import io.cartogra.gateway.domain.TenantBootstrapService;
import io.cartogra.gateway.domain.Tenant;
import io.cartogra.gateway.domain.User;
import io.cartogra.gateway.repository.TenantRepository;
import io.cartogra.gateway.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
    "app.bootstrap.tenant.tenant-id=" + TenantBootstrapIT.TENANT,
    "app.bootstrap.tenant.name=Bootstrap Org",
    "app.bootstrap.tenant.plan=business",
    "app.bootstrap.tenant.admin-email=" + TenantBootstrapIT.EMAIL,
    "app.bootstrap.tenant.admin-password=" + TenantBootstrapIT.PASSWORD,
    "app.signup.enabled=false",
    "app.rate-limit.auth-burst-capacity=1000"
})
class TenantBootstrapIT extends AbstractGatewayIT {

    static final String TENANT = "a0000000-0000-4000-8000-0000000b0075";
    static final String EMAIL = "admin@bootstrap.test";
    static final String PASSWORD = "bootstrap-password-123";

    @Autowired
    private TenantBootstrapService tenantBootstrapService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void createsTenantWithFixedIdOnConfiguredPlanAndVerifiedAdmin() {
        tenantBootstrapService.ensureExists();

        Tenant tenant = tenantRepository.findByTenantId(UUID.fromString(TENANT)).orElseThrow();
        assertThat(tenant.name()).isEqualTo("Bootstrap Org");
        assertThat(tenant.plan()).isEqualTo("business");

        User admin = userRepository.findByTenantAndEmail(UUID.fromString(TENANT), EMAIL).orElseThrow();
        assertThat(admin.emailVerified()).isTrue();
        assertThat(admin.roles()).containsExactly("ADMIN");
    }

    @Test
    void isIdempotent() {
        tenantBootstrapService.ensureExists();
        tenantBootstrapService.ensureExists();

        assertThat(userRepository.findAllByTenant(UUID.fromString(TENANT))).hasSize(1);
    }

    @Test
    void adminCanLogInWhileSignupIsOff() throws Exception {
        tenantBootstrapService.ensureExists();

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s","tenantId":"%s"}
                    """.formatted(EMAIL, PASSWORD, TENANT)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }
}
