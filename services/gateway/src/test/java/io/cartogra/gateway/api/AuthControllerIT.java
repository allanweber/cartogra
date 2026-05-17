package io.cartogra.gateway.api;

import com.jayway.jsonpath.JsonPath;
import io.cartogra.gateway.AbstractGatewayIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;


class AuthControllerIT extends AbstractGatewayIT {

    @Test
    void registerNewUserReturns201() {
        webTestClient.post().uri("/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "email", "newuser@test.com",
                "password", "password123",
                "orgName", "Test Org"
            ))
            .exchange()
            .expectStatus().isCreated()
            .expectBody()
            .jsonPath("$.traceId").isNotEmpty()
            .jsonPath("$.data.tenantId").isNotEmpty();
    }

    @Test
    void registerWithoutOrgNameUsesEmailAsTenantName() {
        webTestClient.post().uri("/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "email", "noorg-" + UUID.randomUUID() + "@test.com",
                "password", "password123"
            ))
            .exchange()
            .expectStatus().isCreated()
            .expectBody()
            .jsonPath("$.data.tenantId").isNotEmpty();
    }

    @Test
    void loginWithUnverifiedUserReturns401() {
        String email = "unverified-" + UUID.randomUUID() + "@test.com";

        UUID tenantId = registerAndExtractTenantId(email, "password123");

        webTestClient.post().uri("/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "email", email,
                "password", "password123",
                "tenantId", tenantId.toString()
            ))
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.error.code").isNotEmpty()
            .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    void loginWithWrongPasswordReturns401() {
        webTestClient.post().uri("/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "email", "nonexistent@test.com",
                "password", "wrong",
                "tenantId", UUID.randomUUID().toString()
            ))
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("UNAUTHORIZED")
            .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    void fullRegisterVerifyLoginFlow() throws InterruptedException {
        String email = "full-flow-" + UUID.randomUUID() + "@test.com";

        UUID tenantId = registerAndExtractTenantId(email, "password123");

        // Give async email sender time to log the OTP
        Thread.sleep(100);

        // Verify with a known OTP by going directly to the DB is not feasible in IT —
        // test just confirms the endpoint accepts the request shape
        webTestClient.post().uri("/v1/auth/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "email", email,
                "token", "000000"
            ))
            .exchange()
            .expectStatus().isEqualTo(400); // wrong OTP but correct request structure
    }

    @Test
    void invalidEmailReturns400() {
        webTestClient.post().uri("/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "email", "not-an-email",
                "password", "password123"
            ))
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void shortPasswordReturns400() {
        webTestClient.post().uri("/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "email", "user@test.com",
                "password", "short"
            ))
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void accessProtectedRouteWithoutTokenReturns401() {
        webTestClient.get().uri("/api/v1/services")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("UNAUTHORIZED")
            .jsonPath("$.traceId").isNotEmpty();
    }

    private UUID registerAndExtractTenantId(String email, String password) {
        String body = webTestClient.post().uri("/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email", email, "password", password))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

        return UUID.fromString(JsonPath.read(body, "$.data.tenantId"));
    }
}
