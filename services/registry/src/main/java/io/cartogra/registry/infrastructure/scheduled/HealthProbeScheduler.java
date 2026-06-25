package io.cartogra.registry.infrastructure.scheduled;

import io.cartogra.registry.domain.ServiceHealthService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HealthProbeScheduler {

    private final ServiceHealthService serviceHealthService;

    public HealthProbeScheduler(ServiceHealthService serviceHealthService) {
        this.serviceHealthService = serviceHealthService;
    }

    @Scheduled(fixedDelayString = "${registry.health.probe-interval:PT60S}")
    public void probe() {
        serviceHealthService.probeAll();
    }
}
