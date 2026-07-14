package io.cartogra.registry.api;

import io.cartogra.common.api.PageResult;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.ServiceHealthStatus;
import io.cartogra.registry.domain.ServiceService;
import io.cartogra.registry.domain.ServiceSnapshot;
import io.cartogra.registry.domain.exception.ServiceNotFoundException;
import io.cartogra.registry.repository.ServiceFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ServiceControllerTest {

    @Mock
    ServiceService serviceService;

    MockMvc mockMvc;

    private static final UUID TENANT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ServiceController(serviceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void createReturns201WithEnvelopeAndTraceHeader() throws Exception {
        Service svc = service("payments");
        when(serviceService.create(eq(TENANT), any(), isNull())).thenReturn(svc);

        mockMvc.perform(post("/services")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"payments"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.name").value("payments"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void createForwardsXUserIdToService() throws Exception {
        UUID userId = UUID.randomUUID();
        when(serviceService.create(eq(TENANT), any(), eq(userId))).thenReturn(service("payments"));

        mockMvc.perform(post("/services")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"payments"}"""))
                .andExpect(status().isCreated());

        verify(serviceService).create(eq(TENANT), any(), eq(userId));
    }

    @Test
    void createWithBlankNameReturns400WithValidationError() throws Exception {
        mockMvc.perform(post("/services")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createMissingTenantHeaderReturns400() throws Exception {
        mockMvc.perform(post("/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"payments"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(serviceService.get(TENANT, id)).thenReturn(service("payments"));

        mockMvc.perform(get("/services/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.data.name").value("payments"));
    }

    @Test
    void getNotFoundReturns404WithErrorCode() throws Exception {
        UUID id = UUID.randomUUID();
        when(serviceService.get(TENANT, id)).thenThrow(new ServiceNotFoundException(id));

        mockMvc.perform(get("/services/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void listReturns200WithPage() throws Exception {
        var page = PageResult.of(List.of(service("svc-1"), service("svc-2")), 2L, 20, 0);
        when(serviceService.list(eq(TENANT), any(ServiceFilter.class), eq(20), eq(0))).thenReturn(page);

        mockMvc.perform(get("/services")
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void listPassesFilterAndPaginationToService() throws Exception {
        UUID teamId = UUID.randomUUID();
        when(serviceService.list(eq(TENANT), any(), anyInt(), anyInt()))
                .thenReturn(PageResult.of(List.of(), 0L, 10, 5));

        mockMvc.perform(get("/services")
                        .header("X-Tenant-Id", TENANT)
                        .param("teamId", teamId.toString())
                        .param("health", "healthy")
                        .param("limit", "10")
                        .param("offset", "5"))
                .andExpect(status().isOk());

        verify(serviceService).list(eq(TENANT),
                eq(new ServiceFilter(teamId, false, "healthy", null, null, null)), eq(10), eq(5));
    }

    @Test
    void orphanedReturns200WithPage() throws Exception {
        var page = PageResult.of(List.of(service("orphan")), 1L, 20, 0);
        when(serviceService.detectOrphans(TENANT, 20, 0)).thenReturn(page);

        mockMvc.perform(get("/services/orphaned")
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void updateReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(serviceService.update(eq(TENANT), eq(id), any(), isNull())).thenReturn(service("updated-svc"));

        mockMvc.perform(put("/services/{id}", id)
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"updated-svc"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("updated-svc"));
    }

    @Test
    void deleteReturns204WithTraceHeader() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/services/{id}", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("X-Trace-Id"));

        verify(serviceService).delete(eq(TENANT), eq(id), isNull());
    }

    @Test
    void deleteAsOwningTeamMemberReturns204() throws Exception {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete("/services/{id}", id)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-User-Id", userId))
                .andExpect(status().isNoContent());

        verify(serviceService).delete(eq(TENANT), eq(id), eq(userId));
    }

    @Test
    void deleteReturns403WhenServiceDeniesAccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        doThrow(new AccessDeniedException("Only ADMIN or a member of the owning team may edit this service"))
                .when(serviceService).delete(eq(TENANT), eq(id), eq(userId));

        mockMvc.perform(delete("/services/{id}", id)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-User-Id", userId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void assignOwnerReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        when(serviceService.assignOwner(eq(TENANT), eq(id), eq(teamId), isNull())).thenReturn(service("payments"));

        mockMvc.perform(patch("/services/{id}/owner", id)
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamId":"%s"}""".formatted(teamId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("payments"));
    }

    @Test
    void removeOwnerReturns200WithNullTeamId() throws Exception {
        UUID id = UUID.randomUUID();
        when(serviceService.assignOwner(eq(TENANT), eq(id), isNull(), isNull())).thenReturn(service("payments"));

        mockMvc.perform(delete("/services/{id}/owner", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("payments"));

        verify(serviceService).assignOwner(eq(TENANT), eq(id), isNull(), isNull());
    }

    @Test
    void assignOwnerAsOwningTeamMemberReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(serviceService.assignOwner(eq(TENANT), eq(id), eq(teamId), eq(userId))).thenReturn(service("payments"));

        mockMvc.perform(patch("/services/{id}/owner", id)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamId":"%s"}""".formatted(teamId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("payments"));

        verify(serviceService).assignOwner(eq(TENANT), eq(id), eq(teamId), eq(userId));
    }

    @Test
    void assignOwnerReturns403WhenServiceDeniesAccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(serviceService.assignOwner(eq(TENANT), eq(id), eq(teamId), eq(userId)))
                .thenThrow(new AccessDeniedException("Only ADMIN or a member of the owning team may edit this service"));

        mockMvc.perform(patch("/services/{id}/owner", id)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamId":"%s"}""".formatted(teamId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void listWithUnownedFlagPassesFilterToService() throws Exception {
        when(serviceService.list(eq(TENANT), eq(new ServiceFilter(null, true, null, null, null, null)), eq(20), eq(0)))
                .thenReturn(PageResult.of(List.of(), 0L, 20, 0));

        mockMvc.perform(get("/services")
                        .header("X-Tenant-Id", TENANT)
                        .param("unowned", "true"))
                .andExpect(status().isOk());

        verify(serviceService).list(eq(TENANT), eq(new ServiceFilter(null, true, null, null, null, null)), eq(20), eq(0));
    }

    @Test
    void historyReturns200WithPage() throws Exception {
        UUID id = UUID.randomUUID();
        var page = PageResult.of(List.of(snapshot(id)), 1L, 20, 0);
        when(serviceService.history(eq(TENANT), eq(id), eq(20), eq(0))).thenReturn(page);

        mockMvc.perform(get("/services/{id}/history", id)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void historyAtReturns200WithSingleSnapshot() throws Exception {
        UUID id = UUID.randomUUID();
        var snap = snapshot(id);
        when(serviceService.historyAt(eq(TENANT), eq(id), any(Instant.class))).thenReturn(Optional.of(snap));

        mockMvc.perform(get("/services/{id}/history", id)
                        .header("X-Tenant-Id", TENANT)
                        .param("at", "2024-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].serviceId").value(id.toString()));
    }

    @Test
    void countsByConnectionReturns200WithEnvelopeAndTraceHeader() throws Exception {
        UUID connId = UUID.randomUUID();
        when(serviceService.countByConnectionId(TENANT)).thenReturn(Map.of(connId, 3L));

        mockMvc.perform(get("/services/counts-by-connection")
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.data.counts." + connId).value(3));
    }

    @Test
    void countsByConnectionReturnsEmptyMapWhenNoServices() throws Exception {
        when(serviceService.countByConnectionId(TENANT)).thenReturn(Map.of());

        mockMvc.perform(get("/services/counts-by-connection")
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts").isMap());
    }

    @Test
    void countsByConnectionMissingTenantHeaderReturns400() throws Exception {
        mockMvc.perform(get("/services/counts-by-connection"))
                .andExpect(status().isBadRequest());
    }

    private Service service(String name) {
        Instant now = Instant.now();
        return new Service(UUID.randomUUID(), TENANT, name, null, null, null, null, null,
                ServiceHealthStatus.UNKNOWN, null, now, now, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private ServiceSnapshot snapshot(UUID serviceId) {
        return new ServiceSnapshot(UUID.randomUUID(), serviceId, TENANT, "{}", null, Instant.now());
    }
}
