package io.cartogra.gateway.api;

import io.cartogra.gateway.AbstractGatewayIT;
import io.cartogra.gateway.domain.Tenant;
import io.cartogra.gateway.repository.TenantRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A wrong {@code GUEST_TENANT_ID} fails closed: only a tenant flagged public_sandbox takes guests (ADR-0019 §10). */
@TestPropertySource(properties = {
    "app.guest.tenant-id=" + GuestSandboxFlagIT.TENANT,
    "app.rate-limit.auth-burst-capacity=1000"
})
class GuestSandboxFlagIT extends AbstractGatewayIT {

    static final String TENANT = "6f1d2c3b-0000-4000-8000-00000000ace0";

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void guestModeFollowsTheFlag() throws Exception {
        expectGuest(false);

        UUID id = UUID.fromString(TENANT);
        tenantRepository.save(new Tenant(id, id, "Real customer", null, "free", null, null, null, false));
        expectGuest(false);

        jdbc.sql("UPDATE tenants SET public_sandbox = true WHERE tenant_id = :id").param("id", id).update();
        expectGuest(true);
    }

    private void expectGuest(boolean on) throws Exception {
        mockMvc.perform(get("/api/auth/features"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.guestEnabled").value(on));
        mockMvc.perform(post("/api/auth/guest"))
            .andExpect(on ? status().isOk() : status().isNotFound());
    }
}
