package io.cartogra.gateway.infrastructure.scheduler;

import io.cartogra.gateway.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RefreshTokenPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenPurgeScheduler.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final Duration retentionPeriod;

    public RefreshTokenPurgeScheduler(
            RefreshTokenRepository refreshTokenRepository,
            @Value("${app.refresh-token.purge.retention-period:P7D}") Duration retentionPeriod) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.retentionPeriod = retentionPeriod;
    }

    // Daily at 03:00 UTC
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void purgeExpiredAndRevoked() {
        log.info("Refresh token purge started [retentionPeriod={}]", retentionPeriod);
        long start = System.currentTimeMillis();
        try {
            int deleted = refreshTokenRepository.deleteExpiredAndRevoked(retentionPeriod);
            log.info("Refresh token purge finished [deleted={}, durationMs={}]",
                    deleted, System.currentTimeMillis() - start);
        } catch (Exception ex) {
            log.error("Refresh token purge failed [durationMs={}]",
                    System.currentTimeMillis() - start, ex);
            throw ex;
        }
    }
}
