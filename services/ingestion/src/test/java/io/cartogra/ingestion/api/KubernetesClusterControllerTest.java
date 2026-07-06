package io.cartogra.ingestion.api;

import io.cartogra.common.api.PageResult;
import io.cartogra.ingestion.domain.Cluster;
import io.cartogra.ingestion.domain.ClusterService;
import io.cartogra.ingestion.domain.ClusterSource;
import io.cartogra.ingestion.domain.ClusterStatus;
import io.cartogra.ingestion.domain.exception.ClusterNotFoundException;
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
class KubernetesClusterControllerTest {

    @Mock
    ClusterService clusterService;

    MockMvc mockMvc;

    private static final UUID TENANT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new KubernetesClusterController(clusterService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void createReturns201WithEnvelopeAndTraceHeader() throws Exception {
        when(clusterService.create(eq(TENANT), any())).thenReturn(cluster("prod"));

        mockMvc.perform(post("/k8s/clusters")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"prod","source":"MANUAL","apiServerUrl":"https://1.2.3.4:6443"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.name").value("prod"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void createWithBlankNameReturns400ValidationError() throws Exception {
        mockMvc.perform(post("/k8s/clusters")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","source":"MANUAL","apiServerUrl":"https://1.2.3.4:6443"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createWithInvalidSourceEnumReturns400() throws Exception {
        mockMvc.perform(post("/k8s/clusters")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"prod","source":"UNKNOWN","apiServerUrl":"https://1.2.3.4:6443"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createMissingTenantHeaderReturns400() throws Exception {
        mockMvc.perform(post("/k8s/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"prod","source":"MANUAL","apiServerUrl":"https://1.2.3.4:6443"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createServiceRejectsIllegalArgumentReturns400() throws Exception {
        when(clusterService.create(eq(TENANT), any()))
                .thenThrow(new IllegalArgumentException("apiServerUrl is required when source is MANUAL"));

        mockMvc.perform(post("/k8s/clusters")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"prod","source":"MANUAL"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void listReturns200WithPage() throws Exception {
        var page = PageResult.of(List.of(cluster("a"), cluster("b")), 2L, 20, 0);
        when(clusterService.list(TENANT, 20, 0)).thenReturn(page);

        mockMvc.perform(get("/k8s/clusters")
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void listPassesPaginationParams() throws Exception {
        when(clusterService.list(TENANT, 10, 5)).thenReturn(PageResult.of(List.of(), 0L, 10, 5));

        mockMvc.perform(get("/k8s/clusters")
                        .header("X-Tenant-Id", TENANT)
                        .param("limit", "10")
                        .param("offset", "5"))
                .andExpect(status().isOk());

        verify(clusterService).list(TENANT, 10, 5);
    }

    @Test
    void getReturns200WithCluster() throws Exception {
        UUID id = UUID.randomUUID();
        when(clusterService.get(TENANT, id)).thenReturn(cluster("prod"));

        mockMvc.perform(get("/k8s/clusters/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.name").value("prod"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void getNotFoundReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(clusterService.get(TENANT, id)).thenThrow(new ClusterNotFoundException(id));

        mockMvc.perform(get("/k8s/clusters/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void deleteReturns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/k8s/clusters/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNoContent());

        verify(clusterService).delete(TENANT, id);
    }

    @Test
    void deleteNotFoundReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ClusterNotFoundException(id))
                .when(clusterService).delete(TENANT, id);

        mockMvc.perform(delete("/k8s/clusters/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void updateReturns200WithEnvelopeAndTraceHeader() throws Exception {
        UUID id = UUID.randomUUID();
        when(clusterService.update(eq(TENANT), eq(id), any())).thenReturn(cluster("prod-updated"));

        mockMvc.perform(put("/k8s/clusters/{id}", id)
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"prod-updated","source":"MANUAL","apiServerUrl":"https://1.2.3.4:6443"}"""))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.name").value("prod-updated"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void updateNotFoundReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(clusterService.update(eq(TENANT), eq(id), any()))
                .thenThrow(new ClusterNotFoundException(id));

        mockMvc.perform(put("/k8s/clusters/{id}", id)
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"prod","source":"MANUAL","apiServerUrl":"https://1.2.3.4:6443"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void updateServiceRejectsInvalidKubeconfigReturns400() throws Exception {
        UUID id = UUID.randomUUID();
        when(clusterService.update(eq(TENANT), eq(id), any()))
                .thenThrow(new IllegalArgumentException("kubeconfig is missing a cluster server URL"));

        mockMvc.perform(put("/k8s/clusters/{id}", id)
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"prod","source":"KUBECONFIG","kubeconfig":"kind: Config"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void updateMissingTenantHeaderReturns400() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/k8s/clusters/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"prod","source":"MANUAL","apiServerUrl":"https://1.2.3.4:6443"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void responseDoesNotIncludeCredentialFields() throws Exception {
        UUID id = UUID.randomUUID();
        when(clusterService.get(TENANT, id)).thenReturn(cluster("prod"));

        mockMvc.perform(get("/k8s/clusters/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caCertPem").doesNotExist())
                .andExpect(jsonPath("$.data.saToken").doesNotExist());
    }

    private Cluster cluster(String name) {
        Instant now = Instant.now();
        return new Cluster(UUID.randomUUID(), TENANT, name, ClusterSource.MANUAL,
                "https://1.2.3.4:6443", null, null, false,
                ClusterStatus.CONNECTING, null, now, now, null, null);
    }
}
