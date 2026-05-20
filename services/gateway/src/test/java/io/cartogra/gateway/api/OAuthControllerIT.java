package io.cartogra.gateway.api;

import io.cartogra.gateway.AbstractGatewayIT;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OAuthControllerIT extends AbstractGatewayIT {

    @Test
    void oauthStartRedirectsToProvider() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(get("/v1/auth/oauth/google/start")
                .param("tenantId", tenantId.toString()))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("accounts.google.com")));
    }

    @Test
    void oauthStartWithGithubRedirectsToGithub() throws Exception {
        UUID tenantId = UUID.randomUUID();

        mockMvc.perform(get("/v1/auth/oauth/github/start")
                .param("tenantId", tenantId.toString()))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("github.com")));
    }

    @Test
    void oauthCallbackWithInvalidStateReturns400() throws Exception {
        mockMvc.perform(get("/v1/auth/oauth/google/callback")
                .param("code", "authcode")
                .param("state", "invalid-state"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").isNotEmpty())
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
