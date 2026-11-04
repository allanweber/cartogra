package io.cartogra.gateway.api;

import io.cartogra.gateway.AbstractGatewayIT;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GuestDisabledIT extends AbstractGatewayIT {

    @Test
    void mintIsNotFoundWithoutDemoTenant() throws Exception {
        mockMvc.perform(post("/api/auth/guest")).andExpect(status().isNotFound());
    }

    @Test
    void featuresDefaultToSignupOnGuestOff() throws Exception {
        mockMvc.perform(get("/api/auth/features"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.guestEnabled").value(false))
            .andExpect(jsonPath("$.data.signupEnabled").value(true));
    }
}
