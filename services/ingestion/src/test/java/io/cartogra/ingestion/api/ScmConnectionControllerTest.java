package io.cartogra.ingestion.api;

import io.cartogra.common.api.PageResult;
import io.cartogra.ingestion.domain.ScmConnection;
import io.cartogra.ingestion.domain.ScmConnectionService;
import io.cartogra.ingestion.domain.exception.ScmConnectionNotFoundException;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ScmConnectionControllerTest {

    @Mock
    ScmConnectionService scmConnectionService;

    MockMvc mockMvc;

    private static final UUID TENANT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ScmConnectionController(scmConnectionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void createReturns201WithEnvelopeAndTraceHeader() throws Exception {
        when(scmConnectionService.create(eq(TENANT), any())).thenReturn(connection());

        mockMvc.perform(post("/scm-connections")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"github","config":"{\\"token\\":\\"ghp_test\\"}"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.provider").value("github"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void createWithInvalidProviderReturns400() throws Exception {
        mockMvc.perform(post("/scm-connections")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"bitbucket","config":"{}"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createWithProviderExceedingMaxLengthReturns400() throws Exception {
        mockMvc.perform(post("/scm-connections")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"%s","config":"{}"}""".formatted("x".repeat(51))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createMissingTenantHeaderReturns400() throws Exception {
        mockMvc.perform(post("/scm-connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"github","config":"{}"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void responseScrubbedOfSecretConfigKeys() throws Exception {
        ScmConnection conn = new ScmConnection(UUID.randomUUID(), TENANT, "github",
                "{\"org\":\"acme\",\"token\":\"secret\",\"pat\":\"also-secret\"}",
                true, 60, null, null, null, false, Instant.now(), Instant.now(), null);
        when(scmConnectionService.create(eq(TENANT), any())).thenReturn(conn);

        mockMvc.perform(post("/scm-connections")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"github","config":"{\\"token\\":\\"secret\\"}"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.config.token").doesNotExist())
                .andExpect(jsonPath("$.data.config.pat").doesNotExist())
                .andExpect(jsonPath("$.data.config.org").value("acme"));
    }

    @Test
    void listReturns200WithPage() throws Exception {
        var page = PageResult.of(List.of(connection(), connection()), 2L, 20, 0);
        when(scmConnectionService.list(TENANT, 20, 0)).thenReturn(page);

        mockMvc.perform(get("/scm-connections")
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void listPassesPaginationParams() throws Exception {
        when(scmConnectionService.list(TENANT, 5, 10)).thenReturn(PageResult.of(List.of(), 0L, 5, 10));

        mockMvc.perform(get("/scm-connections")
                        .header("X-Tenant-Id", TENANT)
                        .param("limit", "5")
                        .param("offset", "10"))
                .andExpect(status().isOk());

        verify(scmConnectionService).list(TENANT, 5, 10);
    }

    @Test
    void getReturns200WithConnection() throws Exception {
        UUID id = UUID.randomUUID();
        when(scmConnectionService.get(TENANT, id)).thenReturn(connection());

        mockMvc.perform(get("/scm-connections/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.provider").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void getNotFoundReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(scmConnectionService.get(TENANT, id)).thenThrow(new ScmConnectionNotFoundException(id));

        mockMvc.perform(get("/scm-connections/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void updateReturns200WithUpdatedConnection() throws Exception {
        UUID id = UUID.randomUUID();
        when(scmConnectionService.update(eq(TENANT), eq(id), any())).thenReturn(connection());

        mockMvc.perform(put("/scm-connections/{id}", id)
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pollIntervalMinutes":30}"""))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.provider").exists());
    }

    @Test
    void updateNotFoundReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(scmConnectionService.update(eq(TENANT), eq(id), any()))
                .thenThrow(new ScmConnectionNotFoundException(id));

        mockMvc.perform(put("/scm-connections/{id}", id)
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pollIntervalMinutes":30}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void updateWithExcessivePollIntervalReturns400() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/scm-connections/{id}", id)
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pollIntervalMinutes":9999}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void deleteReturns204WithTraceHeader() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/scm-connections/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("X-Trace-Id"));

        verify(scmConnectionService).delete(TENANT, id);
    }

    @Test
    void deleteNotFoundReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ScmConnectionNotFoundException(id))
                .when(scmConnectionService).delete(TENANT, id);

        mockMvc.perform(delete("/scm-connections/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void triggerSyncReturns202() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/scm-connections/{id}/sync", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("X-Trace-Id"));

        verify(scmConnectionService).triggerSync(TENANT, id);
    }

    @Test
    void triggerSyncNotFoundReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ScmConnectionNotFoundException(id))
                .when(scmConnectionService).triggerSync(TENANT, id);

        mockMvc.perform(post("/scm-connections/{id}/sync", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    private ScmConnection connection() {
        return new ScmConnection(UUID.randomUUID(), TENANT, "github",
                "{\"org\":\"acme\"}", true, 60, null, null, null,
                false, Instant.now(), Instant.now(), null);
    }
}
