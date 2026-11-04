package io.cartogra.gateway.api;

import com.jayway.jsonpath.JsonPath;
import io.cartogra.gateway.AbstractGatewayIT;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import io.cartogra.web.security.WriteRoutes;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
    "app.guest.tenant-id=" + GuestModeIT.DEMO_TENANT,
    "app.signup.enabled=false",
    "app.rate-limit.auth-burst-capacity=1000"
})
class GuestModeIT extends AbstractGatewayIT {

    static final String DEMO_TENANT = "6f1d2c3b-0000-4000-8000-00000000acde";

    private static final Set<String> GUEST_ALLOWED_WRITES = Set.of("/api/auth/guest", "/api/auth/logout");

    private static final List<String> PROXIED_WRITE_PATHS = List.of(
        "/api/v1/registry/services",
        "/api/v1/registry/teams/" + UUID.randomUUID(),
        "/api/v1/topology/dependencies",
        "/api/v1/topology/drifts/" + UUID.randomUUID() + "/resolve",
        "/api/v1/ingestion/scm-connections",
        "/api/v1/not-yet-built/anything");

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private WebApplicationContext context;

    @Test
    void mintIssuesShortLivedReadOnlyTokenForDemoTenant() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/guest"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andReturn();

        Cookie jwt = result.getResponse().getCookie("jwt");
        assertThat(jwt).isNotNull();
        assertThat(jwt.isHttpOnly()).isTrue();
        assertThat(result.getResponse().getCookie("jwt_refresh")).isNull();

        JwtClaims claims = jwtTokenProvider.decode(jwt.getValue());
        assertThat(claims.roles()).containsExactly("GUEST");
        assertThat(claims.tenantId()).isEqualTo(UUID.fromString(DEMO_TENANT));
        assertThat(claims.userId()).isNotNull();
        assertThat(claims.exp()).isBetween(Instant.now().plus(Duration.ofMinutes(59)),
            Instant.now().plus(Duration.ofMinutes(61)));
    }

    @Test
    void eachMintIsADistinctGuest() throws Exception {
        assertThat(jwtTokenProvider.decode(mintGuest().getValue()).userId())
            .isNotEqualTo(jwtTokenProvider.decode(mintGuest().getValue()).userId());
    }

    @Test
    void guestIsForbiddenOnEveryGatewayAndProxiedWriteRoute() throws Exception {
        Cookie guest = mintGuest();
        List<String> targets = new ArrayList<>();
        WriteRoutes.of(context).stream()
            .filter(r -> !GUEST_ALLOWED_WRITES.contains(r.path()))
            .forEach(r -> targets.add(r.method() + " " + r.path()));
        for (String path : PROXIED_WRITE_PATHS) {
            for (HttpMethod m : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
                targets.add(m + " " + path);
            }
        }
        assertThat(targets).contains("POST /api/auth/admin/invite", "PUT /api/auth/userinfo");

        List<String> notForbidden = new ArrayList<>();
        for (String target : targets) {
            String[] parts = target.split(" ", 2);
            int status = mockMvc.perform(request(HttpMethod.valueOf(parts[0]), parts[1])
                    .cookie(guest)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andReturn().getResponse().getStatus();
            if (status != 403) {
                notForbidden.add(target + " -> " + status);
            }
        }
        assertThat(notForbidden).isEmpty();
    }

    @Test
    void guestCanRead() throws Exception {
        Cookie guest = mintGuest();
        mockMvc.perform(get("/api/auth/userinfo").cookie(guest))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.roles[0]").value("GUEST"))
            .andExpect(jsonPath("$.data.tenantId").value(DEMO_TENANT));

        int proxiedRead = mockMvc.perform(get("/api/v1/registry/services").cookie(guest))
            .andReturn().getResponse().getStatus();
        assertThat(proxiedRead).isNotIn(401, 403);
    }

    @Test
    void guestCannotReadUserDirectoryOrTenantDetails() throws Exception {
        Cookie guest = mintGuest();

        mockMvc.perform(get("/api/auth/users").param("ids", UUID.randomUUID().toString()).cookie(guest))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auth/tenant").cookie(guest))
            .andExpect(status().isForbidden());
    }

    @Test
    void guestCanReMintAndLogOut() throws Exception {
        Cookie guest = mintGuest();
        mockMvc.perform(post("/api/auth/guest").cookie(guest)).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/logout").cookie(guest)).andExpect(status().isOk());
    }

    @Test
    void featuresReportGuestOnAndSignupOff() throws Exception {
        mockMvc.perform(get("/api/auth/features"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.guestEnabled").value(true))
            .andExpect(jsonPath("$.data.signupEnabled").value(false));
    }

    @Test
    void registerIsRefusedWhenSignupDisabled() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"signup-off-%s@test.com","password":"Password1!"}
                    """.formatted(UUID.randomUUID())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("SIGNUP_DISABLED"));
    }

    private Cookie mintGuest() throws Exception {
        String body = mockMvc.perform(post("/api/auth/guest"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return new Cookie("jwt", JsonPath.read(body, "$.data.accessToken"));
    }
}
