package io.cartogra.registry.domain;

import io.cartogra.registry.infrastructure.http.ServiceHealthChecker;
import io.cartogra.registry.repository.AdvisoryLockRepository;
import io.cartogra.registry.repository.ServiceHistoryRepository;
import io.cartogra.registry.repository.ServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceHealthServiceTest {

    @Mock ServiceRepository serviceRepository;
    @Mock ServiceHistoryRepository historyRepository;
    @Mock ServiceHealthChecker healthChecker;
    @Mock AdvisoryLockRepository advisoryLockRepository;

    private ServiceHealthService service;

    @BeforeEach
    void setUp() {
        service = new ServiceHealthService(
                serviceRepository, historyRepository, healthChecker,
                advisoryLockRepository, new ObjectMapper());
    }

    @Test
    void lockNotAcquiredSkipsAllProbes() {
        when(advisoryLockRepository.tryAcquireLock(anyLong())).thenReturn(false);

        service.probeAll();

        verify(serviceRepository, never()).findAllWithHealthEndpoint();
        verify(healthChecker, never()).check(any());
    }

    @Test
    void statusUnchangedUpdatesCheckedAtButSkipsSnapshot() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Service svc = service(tenantId, serviceId, ServiceHealthStatus.HEALTHY, "http://example.com/health");

        when(advisoryLockRepository.tryAcquireLock(anyLong())).thenReturn(true);
        when(serviceRepository.findAllWithHealthEndpoint()).thenReturn(List.of(svc));
        when(healthChecker.check("http://example.com/health")).thenReturn(ServiceHealthStatus.HEALTHY);

        service.probeAll();

        verify(serviceRepository).updateHealth(eq(tenantId), eq(serviceId),
                eq(ServiceHealthStatus.HEALTHY), any(Instant.class));
        verify(historyRepository, never()).save(any());
        verify(advisoryLockRepository).releaseLock(anyLong());
    }

    @Test
    void statusChangedSavesSnapshot() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Service svc = service(tenantId, serviceId, ServiceHealthStatus.UNKNOWN, "http://example.com/health");

        when(advisoryLockRepository.tryAcquireLock(anyLong())).thenReturn(true);
        when(serviceRepository.findAllWithHealthEndpoint()).thenReturn(List.of(svc));
        when(healthChecker.check("http://example.com/health")).thenReturn(ServiceHealthStatus.HEALTHY);

        service.probeAll();

        verify(serviceRepository).updateHealth(eq(tenantId), eq(serviceId),
                eq(ServiceHealthStatus.HEALTHY), any(Instant.class));
        verify(historyRepository).save(any());
    }

    @Test
    void unexpectedExceptionMarksUnhealthyAndContinues() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId1 = UUID.randomUUID();
        UUID serviceId2 = UUID.randomUUID();
        Service bad = service(tenantId, serviceId1, ServiceHealthStatus.UNKNOWN, "http://bad.example.com/health");
        Service good = service(tenantId, serviceId2, ServiceHealthStatus.UNKNOWN, "http://good.example.com/health");

        when(advisoryLockRepository.tryAcquireLock(anyLong())).thenReturn(true);
        when(serviceRepository.findAllWithHealthEndpoint()).thenReturn(List.of(bad, good));
        when(healthChecker.check("http://bad.example.com/health")).thenThrow(new RuntimeException("boom"));
        when(healthChecker.check("http://good.example.com/health")).thenReturn(ServiceHealthStatus.HEALTHY);

        service.probeAll();

        verify(serviceRepository).updateHealth(eq(tenantId), eq(serviceId1),
                eq(ServiceHealthStatus.UNHEALTHY), any(Instant.class));
        verify(serviceRepository).updateHealth(eq(tenantId), eq(serviceId2),
                eq(ServiceHealthStatus.HEALTHY), any(Instant.class));
    }

    @Test
    void lockAlwaysReleasedEvenOnException() {
        when(advisoryLockRepository.tryAcquireLock(anyLong())).thenReturn(true);
        when(serviceRepository.findAllWithHealthEndpoint()).thenThrow(new RuntimeException("db down"));

        try {
            service.probeAll();
        } catch (RuntimeException _) {
            // expected
        }

        verify(advisoryLockRepository).releaseLock(anyLong());
    }

    private Service service(UUID tenantId, UUID id, ServiceHealthStatus status, String healthEndpoint) {
        Instant now = Instant.now();
        return new Service(id, tenantId, "svc-" + id, null, null, null, null, null,
                status, null, now, now, null,
                null, null, null, null, null, null, null, healthEndpoint, null, null, null,
                null, null, null, null, null);
    }
}
