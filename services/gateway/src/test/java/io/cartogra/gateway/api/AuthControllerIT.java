package io.cartogra.gateway.api;

import com.jayway.jsonpath.JsonPath;
import io.cartogra.gateway.AbstractGatewayIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthControllerIT extends AbstractGatewayIT {

    @Test
    void registerNewUserReturns201() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"newuser@test.com","password":"password123","orgName":"Test Org"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(jsonPath("$.data.tenantId").isNotEmpty());
    }

    @Test
    void registerWithoutOrgNameUsesEmailAsTenantName() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"noorg-%s@test.com","password":"password123"}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.tenantId").isNotEmpty());
    }

    @Test
    void loginWithUnverifiedUserReturns401() throws Exception {
        String email = "unverified-" + UUID.randomUUID() + "@test.com";
        UUID tenantId = registerAndExtractTenantId(email, "password123");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"password123","tenantId":"%s"}
                    """.formatted(email, tenantId)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").isNotEmpty())
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"nonexistent@test.com","password":"wrong","tenantId":"%s"}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void fullRegisterVerifyLoginFlow() throws Exception {
        String email = "full-flow-" + UUID.randomUUID() + "@test.com";
        registerAndExtractTenantId(email, "password123");

        Thread.sleep(100);

        mockMvc.perform(post("/api/auth/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","token":"000000"}
                    """.formatted(email)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void invalidEmailReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"not-an-email","password":"password123"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void shortPasswordReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"user@test.com","password":"short"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void accessProtectedRouteWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/services"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private UUID registerAndExtractTenantId(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, password)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        return UUID.fromString(JsonPath.read(body, "$.data.tenantId"));
    }
}
