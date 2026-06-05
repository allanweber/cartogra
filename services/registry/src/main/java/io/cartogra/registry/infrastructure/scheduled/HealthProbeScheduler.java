package io.cartogra.registry.infrastructure.scheduled;

import io.cartogra.registry.application.usecase.ProbeServiceHealthUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HealthProbeScheduler {

    private final ProbeServiceHealthUseCase probeServiceHealthUseCase;

    public HealthProbeScheduler(ProbeServiceHealthUseCase probeServiceHealthUseCase) {
        this.probeServiceHealthUseCase = probeServiceHealthUseCase;
    }

    @Scheduled(fixedDelayString = "${registry.health.probe-interval:PT60S}")
    public void probe() {
        probeServiceHealthUseCase.probeAll();
    }
}
