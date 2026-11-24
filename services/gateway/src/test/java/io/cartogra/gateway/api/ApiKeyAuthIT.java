package io.cartogra.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.cartogra.web.security.InternalClaims;
import io.cartogra.web.security.InternalTokenCodec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** S-8, S-9 and S-10: the API key on the allowlisted endpoint, and only there. */
class ApiKeyAuthIT extends AbstractApiKeyIT {

    private final String RAW = "ctg_live_" + UUID.randomUUID();

    @Autowired
    private InternalTokenCodec codec;

    @Test
    void headerIsIgnoredOffTheAllowlist() throws Exception {
        seedActiveKey(RAW, tenantA);

        assertThat(send("GET", SERVICES_PATH, RAW, null)).isEqualTo(401);
        assertThat(downstreamRequests()).isEmpty();
    }

    @Test
    void jwtAppliesOffTheAllowlistEvenWithAKeyHeader() throws Exception {
        seedActiveKey(RAW, tenantA);

        assertThat(send("GET", SERVICES_PATH, RAW, jwt(tenantA, "ADMIN"))).isEqualTo(200);

        LoggedRequest seen = downstreamRequests().getFirst();
        assertThat(seen.containsHeader(KEY_HEADER)).isFalse();
        InternalClaims claims = codec.verify(seen.getHeader("X-Internal-Token"), "registry").orElseThrow();
        assertThat(claims.tenantId()).isEqualTo(tenantA);
        assertThat(claims.roles()).containsExactly("ADMIN");
        assertThat(claims.subject()).doesNotStartWith("apikey:");
    }

    @Test
    void jwtWithNoKeyIsForbiddenOnTheAllowlistedEndpoint() throws Exception {
        assertThat(send("POST", CHECK_PATH, null, jwt(tenantA, "ADMIN"))).isEqualTo(403);
        assertThat(downstreamRequests()).isEmpty();
    }

    @Test
    void noKeyAndNoJwtIsUnauthorized() throws Exception {
        assertThat(send("POST", CHECK_PATH, null, null)).isEqualTo(401);
    }

    @Test
    void unknownKeyIsUnauthorized() throws Exception {
        assertThat(send("POST", CHECK_PATH, "ctg_live_unknown", null)).isEqualTo(401);
    }

    @Test
    void revokedKeyIsUnauthorized() throws Exception {
        seedKey(RAW, tenantA, new String[] {"ci:check"}, null, Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(send("POST", CHECK_PATH, RAW, null)).isEqualTo(401);
    }

    @Test
    void expiredKeyIsUnauthorized() throws Exception {
        seedKey(RAW, tenantA, new String[] {"ci:check"}, Instant.now().minus(1, ChronoUnit.HOURS), null);

        assertThat(send("POST", CHECK_PATH, RAW, null)).isEqualTo(401);
    }

    @Test
    void keyWithoutTheScopeIsForbidden() throws Exception {
        seedKey(RAW, tenantA, new String[] {"other:scope"}, null, null);

        assertThat(send("POST", CHECK_PATH, RAW, null)).isEqualTo(403);
        assertThat(downstreamRequests()).isEmpty();
    }

    @Test
    void keyWinsOverAJwtOfAnotherTenant() throws Exception {
        UUID keyId = seedActiveKey(RAW, tenantA);

        assertThat(send("POST", CHECK_PATH, RAW, jwt(tenantB, "ADMIN"))).isEqualTo(200);

        LoggedRequest seen = downstreamRequests().getFirst();
        assertThat(seen.containsHeader(KEY_HEADER)).isFalse();
        InternalClaims claims = codec.verify(seen.getHeader("X-Internal-Token"), "contract").orElseThrow();
        assertThat(claims.subject()).isEqualTo("apikey:" + keyId);
        assertThat(claims.tenantId()).isEqualTo(tenantA);
        assertThat(claims.roles()).containsExactly("CI_CHECK");
    }

    @Test
    void activeKeyIsProxiedAsItsTenantWithTheCiCheckRole() throws Exception {
        UUID keyId = seedActiveKey(RAW, tenantA);

        assertThat(send("POST", CHECK_PATH, RAW, null)).isEqualTo(200);

        LoggedRequest seen = downstreamRequests().getFirst();
        assertThat(seen.containsHeader(KEY_HEADER)).isFalse();
        InternalClaims claims = codec.verify(seen.getHeader("X-Internal-Token"), "contract").orElseThrow();
        assertThat(claims.subject()).isEqualTo("apikey:" + keyId);
        assertThat(claims.tenantId()).isEqualTo(tenantA);
        assertThat(claims.roles()).containsExactly("CI_CHECK");
    }

    @Test
    void trailingSlashIsOnTheAllowlist() throws Exception {
        UUID keyId = seedActiveKey(RAW, tenantA);
        DOWNSTREAM.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
            com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(CHECK_PATH + "/"))
            .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200).withBody("{}")));

        assertThat(send("POST", CHECK_PATH + "/", RAW, null)).isEqualTo(200);

        LoggedRequest seen = downstreamRequests().getFirst();
        InternalClaims claims = codec.verify(seen.getHeader("X-Internal-Token"), "contract").orElseThrow();
        assertThat(claims.subject()).isEqualTo("apikey:" + keyId);
        assertThat(claims.roles()).containsExactly("CI_CHECK");
    }

    @Test
    void revokeWorksOnTheNextRequest() throws Exception {
        UUID keyId = seedActiveKey(RAW, tenantA);
        assertThat(send("POST", CHECK_PATH, RAW, null)).isEqualTo(200);

        superuserJdbc().update("UPDATE api_keys SET revoked_at = now() WHERE id = :id",
            new MapSqlParameterSource("id", keyId));

        assertThat(send("POST", CHECK_PATH, RAW, null)).isEqualTo(401);
    }
}
