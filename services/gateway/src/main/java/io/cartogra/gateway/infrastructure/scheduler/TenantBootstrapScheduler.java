package io.cartogra.gateway.infrastructure.scheduler;

import io.cartogra.gateway.config.TenantBootstrapProperties;
import io.cartogra.gateway.domain.TenantBootstrapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries until the bootstrap tenant exists: registry owns the schema, so on a fresh install the tables may
 * appear after the gateway starts. Runs off the startup path so readiness never waits on it.
 */
@Component
public class TenantBootstrapScheduler {

    private static final Logger log = LoggerFactory.getLogger(TenantBootstrapScheduler.class);

    private final TenantBootstrapService tenantBootstrapService;
    private final boolean enabled;
    private volatile boolean done;

    public TenantBootstrapScheduler(TenantBootstrapService tenantBootstrapService, TenantBootstrapProperties props) {
        this.tenantBootstrapService = tenantBootstrapService;
        this.enabled = props.enabled();
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${app.bootstrap.tenant.retry-delay:PT10S}")
    public void ensureBootstrapTenant() {
        if (!enabled || done) {
            return;
        }
        try {
            tenantBootstrapService.ensureExists();
            done = true;
        } catch (RuntimeException ex) {
            log.warn("tenant_bootstrap.bootstrap_failed, retrying: {}", ex.getMessage());
        }
    }

    public boolean isDone() {
        return done;
    }
}
