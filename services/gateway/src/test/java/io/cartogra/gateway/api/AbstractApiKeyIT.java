package io.cartogra.gateway.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.cartogra.gateway.AbstractGatewayIT;
import io.cartogra.gateway.domain.ApiKeyAuthenticator;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the API key ITs. It runs against a real servlet container with the production filter order, and
 * routes the contract and registry paths to a stub downstream (WireMock).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractApiKeyIT extends AbstractGatewayIT {

    static final String CHECK_PATH = "/api/v1/contracts/check";
    static final String SERVICES_PATH = "/api/v1/registry/services";
    static final String KEY_HEADER = "X-Cartogra-Api-Key";

    static final WireMockServer DOWNSTREAM = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort().http2PlainDisabled(true));

    static {
        DOWNSTREAM.start();
    }

    @DynamicPropertySource
    static void configureRoutes(DynamicPropertyRegistry registry) {
        String prefix = "spring.cloud.gateway.server.webmvc.routes";
        registry.add(prefix + "[0].id", () -> "contract");
        registry.add(prefix + "[0].uri", DOWNSTREAM::baseUrl);
        registry.add(prefix + "[0].predicates[0]", () -> "Path=/api/v1/contracts/**");
        registry.add(prefix + "[1].id", () -> "registry");
        registry.add(prefix + "[1].uri", DOWNSTREAM::baseUrl);
        registry.add(prefix + "[1].predicates[0]", () -> "Path=/api/v1/registry/**");
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    protected UUID tenantA;
    protected UUID tenantB;
    protected UUID userA;

    @BeforeEach
    void resetDownstream() {
        DOWNSTREAM.resetAll();
        DOWNSTREAM.stubFor(post(urlPathEqualTo(CHECK_PATH))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}")));
        DOWNSTREAM.stubFor(get(urlPathEqualTo(SERVICES_PATH))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}")));
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        userA = UUID.randomUUID();
        superuserJdbc().update("""
            INSERT INTO users (id, tenant_id, email, auth_provider)
            VALUES (:id, :tenantId, :email, 'local')
            """, new MapSqlParameterSource().addValue("id", userA).addValue("tenantId", tenantA)
            .addValue("email", userA + "@test.com"));
    }

    /** Inserts a key as the superuser. Returns its id. A null expiry means no expiry. */
    protected UUID seedKey(String rawKey, UUID tenantId, String[] scopes, Instant expiresAt, Instant revokedAt) {
        UUID id = UUID.randomUUID();
        superuserJdbc().update("""
            INSERT INTO api_keys (id, tenant_id, name, key_hash, key_prefix, scopes, expires_at, revoked_at, created_by)
            VALUES (:id, :tenantId, 'ci', :hash, 'ctg_test', CAST(:scopes AS text[]), :expiresAt, :revokedAt, :createdBy)
            """, new MapSqlParameterSource().addValue("id", id).addValue("tenantId", tenantId)
            .addValue("hash", ApiKeyAuthenticator.hash(rawKey))
            .addValue("scopes", scopes == null ? null : "{" + String.join(",", scopes) + "}")
            .addValue("expiresAt", expiresAt == null ? null : java.sql.Timestamp.from(expiresAt))
            .addValue("revokedAt", revokedAt == null ? null : java.sql.Timestamp.from(revokedAt))
            .addValue("createdBy", userA));
        return id;
    }

    protected UUID seedActiveKey(String rawKey, UUID tenantId) {
        return seedKey(rawKey, tenantId, new String[] {"ci:check"}, null, null);
    }

    protected String jwt(UUID tenantId, String role) {
        return jwtTokenProvider.issueAccessToken(new JwtClaims(UUID.randomUUID(), tenantId, "u@test.com", "U",
            "local", List.of(role), Instant.now().plusSeconds(900)));
    }

    /** Sends the request. A null key or a null jwt is not sent. */
    protected int send(String method, String path, String rawKey, String jwt) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (rawKey != null) {
            builder.header(KEY_HEADER, rawKey);
        }
        if (jwt != null) {
            builder.header("Authorization", "Bearer " + jwt);
        }
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        } else {
            builder.GET();
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    protected List<LoggedRequest> downstreamRequests() {
        return DOWNSTREAM.getAllServeEvents().stream().map(e -> e.getRequest()).toList();
    }
}
