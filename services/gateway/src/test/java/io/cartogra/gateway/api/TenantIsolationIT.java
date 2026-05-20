package io.cartogra.gateway.api;

import io.cartogra.gateway.AbstractGatewayIT;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TenantIsolationIT extends AbstractGatewayIT {

    @Test
    void forgedXTenantIdHeaderIsStrippedOnUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/services")
                .header("X-Tenant-Id", UUID.randomUUID().toString()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void forgedXUserIdHeaderIsStrippedOnUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/services")
                .header("X-User-Id", UUID.randomUUID().toString()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void requestWithExpiredJwtIsRejected() throws Exception {
        String expiredJwt = "eyJhbGciOiJIUzI1NiJ9." +
            "eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEiLCJ0aWQiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDIiLCJlbWFpbCI6InVzZXJAdGVzdC5jb20iLCJyb2xlcyI6WyJWSUVXRVIiXSwiaWF0IjoxNjAwMDAwMDAwLCJleHAiOjE2MDAwMDAwMDF9." +
            "invalid-signature";

        mockMvc.perform(get("/api/v1/services")
                .header("Authorization", "Bearer " + expiredJwt))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedJwtIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/services")
                .header("Authorization", "Bearer not.a.valid.jwt.at.all"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedRequestToPublicEndpointSucceeds() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk());
    }
}
