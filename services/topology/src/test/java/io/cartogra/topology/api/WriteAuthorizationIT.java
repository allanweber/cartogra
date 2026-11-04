package io.cartogra.topology.api;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.web.security.WriteRoutes;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
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

class WriteAuthorizationIT extends AbstractTopologyIT {

    @Autowired
    private WebApplicationContext context;

    @ParameterizedTest
    @ValueSource(strings = {"GUEST", "VIEWER"})
    void everyWriteRouteForbidsReadOnlyRoles(String role) throws Exception {
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        var routes = WriteRoutes.of(context, "/internal/");
        assertThat(routes).extracting(WriteRoutes.Route::toString).contains("POST /dependencies");

        List<String> notForbidden = new ArrayList<>();
        for (var route : routes) {
            int status = mvc.perform(request(route.method(), route.path())
                    .header("X-Tenant-Id", UUID.randomUUID().toString())
                    .header("X-User-Id", UUID.randomUUID().toString())
                    .header("X-User-Roles", role)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andReturn().getResponse().getStatus();
            if (status != 403) {
                notForbidden.add(route + " -> " + status);
            }
        }
        assertThat(notForbidden).isEmpty();
    }
}
