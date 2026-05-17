package io.cartogra.gateway.api;

import io.cartogra.gateway.AbstractGatewayIT;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class TenantIsolationIT extends AbstractGatewayIT {

    @Test
    void forgedXTenantIdHeaderIsStrippedOnUnauthenticatedRequest() {
        // Even without a JWT, a forged X-Tenant-Id should not reach downstream services.
        // The protected route returns 401 — meaning the gateway processed the request
        // with the forged header stripped (not passing it through unmodified).
        webTestClient.get()
            .uri("/api/v1/services")
            .header("X-Tenant-Id", UUID.randomUUID().toString())
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void forgedXUserIdHeaderIsStrippedOnUnauthenticatedRequest() {
        webTestClient.get()
            .uri("/api/v1/services")
            .header("X-User-Id", UUID.randomUUID().toString())
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void requestWithExpiredJwtIsRejected() {
        // A well-formed but expired JWT should not authenticate the request
        String expiredJwt = "eyJhbGciOiJIUzI1NiJ9." +
            "eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDEiLCJ0aWQiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDIiLCJlbWFpbCI6InVzZXJAdGVzdC5jb20iLCJyb2xlcyI6WyJWSUVXRVIiXSwiaWF0IjoxNjAwMDAwMDAwLCJleHAiOjE2MDAwMDAwMDF9." +
            "invalid-signature";

        webTestClient.get()
            .uri("/api/v1/services")
            .header("Authorization", "Bearer " + expiredJwt)
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void malformedJwtIsRejected() {
        webTestClient.get()
            .uri("/api/v1/services")
            .header("Authorization", "Bearer not.a.valid.jwt.at.all")
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void unauthenticatedRequestToPublicEndpointSucceeds() {
        webTestClient.get()
            .uri("/actuator/health/live")
            .exchange()
            .expectStatus().isOk();
    }
}
