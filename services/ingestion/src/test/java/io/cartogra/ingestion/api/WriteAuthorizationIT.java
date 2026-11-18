package io.cartogra.ingestion.api;

import io.cartogra.web.security.InternalTokens;
import io.cartogra.web.security.WriteRoutes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
class WriteAuthorizationIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresTestSupport.POSTGRES.start();
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.flyway.schemas", () -> "ingestion");
        registry.add("spring.flyway.default-schema", () -> "ingestion");
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }
    @Autowired
    private WebApplicationContext context;

    @ParameterizedTest
    @ValueSource(strings = {"GUEST", "VIEWER"})
    void everyWriteRouteForbidsReadOnlyRoles(String role) throws Exception {
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var routes = WriteRoutes.of(context, "/internal/", "/webhooks/");
        assertThat(routes).extracting(WriteRoutes.Route::toString).contains("POST /scm-connections");

        List<String> notForbidden = new ArrayList<>();
        for (var route : routes) {
            UUID tenant = UUID.randomUUID();
            int status = mvc.perform(request(route.method(), route.path())
                    .header("X-Tenant-Id", tenant.toString())
                    .header(InternalTokens.HEADER, InternalTokens.token("ingestion", tenant, UUID.randomUUID(), role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andReturn().getResponse().getStatus();
            if (status != 403) {
                notForbidden.add(route + " -> " + status);
            }
        }
        assertThat(notForbidden).isEmpty();
    }

    @Test
    void requestWithoutTokenIsUnauthorizedForReadAndWrite() throws Exception {
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        UUID tenant = UUID.randomUUID();
        for (var method : List.of(HttpMethod.GET, HttpMethod.POST)) {
            int status = mvc.perform(request(method, "/scm-connections")
                    .header("X-Tenant-Id", tenant.toString())
                    .header("X-User-Roles", "ADMIN")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andReturn().getResponse().getStatus();
            assertThat(status).as(method + " /scm-connections").isEqualTo(401);
        }
    }

    @Test
    void tokenForAnotherTenantIsUnauthorized() throws Exception {
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        int status = mvc.perform(request(HttpMethod.GET, "/scm-connections")
                .header("X-Tenant-Id", UUID.randomUUID().toString())
                .header(InternalTokens.HEADER, InternalTokens.token("ingestion", UUID.randomUUID(), UUID.randomUUID(), "ADMIN")))
            .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }
}
