package io.cartogra.registry.api;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.domain.Team;
import io.cartogra.registry.domain.TeamService;
import io.cartogra.registry.domain.exception.TeamNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class TeamControllerTest {

    @Mock
    TeamService teamService;

    MockMvc mockMvc;

    private static final UUID TENANT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TeamController(teamService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void createReturns201WithEnvelopeAndTraceHeader() throws Exception {
        when(teamService.create(TENANT, "platform")).thenReturn(team("platform"));

        mockMvc.perform(post("/teams")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"platform"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.name").value("platform"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void createWithBlankNameReturns400WithValidationError() throws Exception {
        mockMvc.perform(post("/teams")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createMissingTenantHeaderReturns400() throws Exception {
        mockMvc.perform(post("/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"platform"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(teamService.get(TENANT, id)).thenReturn(team("platform"));

        mockMvc.perform(get("/teams/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.name").value("platform"));
    }

    @Test
    void getNotFoundReturns404WithErrorCode() throws Exception {
        UUID id = UUID.randomUUID();
        when(teamService.get(TENANT, id)).thenThrow(new TeamNotFoundException(id));

        mockMvc.perform(get("/teams/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void listReturns200WithPage() throws Exception {
        var page = PageResult.of(List.of(team("platform"), team("backend")), 2L, 20, 0);
        when(teamService.list(TENANT, 20, 0)).thenReturn(page);

        mockMvc.perform(get("/teams")
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void listPassesPaginationToService() throws Exception {
        when(teamService.list(TENANT, 10, 5)).thenReturn(PageResult.of(List.of(), 0L, 10, 5));

        mockMvc.perform(get("/teams")
                        .header("X-Tenant-Id", TENANT)
                        .param("limit", "10")
                        .param("offset", "5"))
                .andExpect(status().isOk());

        verify(teamService).list(TENANT, 10, 5);
    }

    @Test
    void updateReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(teamService.update(TENANT, id, "platform-eng")).thenReturn(team("platform-eng"));

        mockMvc.perform(put("/teams/{id}", id)
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"platform-eng"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("platform-eng"));
    }

    @Test
    void updateNotFoundReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(teamService.update(eq(TENANT), eq(id), anyString())).thenThrow(new TeamNotFoundException(id));

        mockMvc.perform(put("/teams/{id}", id)
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"x"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns204WithTraceHeader() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/teams/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("X-Trace-Id"));

        verify(teamService).delete(TENANT, id);
    }

    @Test
    void deleteNotFoundReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new TeamNotFoundException(id)).when(teamService).delete(TENANT, id);

        mockMvc.perform(delete("/teams/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNotFound());
    }

    private Team team(String name) {
        Instant now = Instant.now();
        return new Team(UUID.randomUUID(), TENANT, name, now, now, null);
    }
}
