package io.cartogra.contract.repository;

import io.cartogra.contract.domain.NotificationRule;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRuleRepository {

    void insert(NotificationRule rule);

    Optional<NotificationRule> findLive(UUID tenantId, UUID id);

    /** Live rules of the tenant, newest first. */
    List<NotificationRule> listLive(UUID tenantId, int limit, int offset);

    long countLive(UUID tenantId);

    /** The tenant-wide rule and the rule of one Service, both even when disabled. One query, never one per destination. */
    List<NotificationRule> findMatching(UUID tenantId, UUID serviceId);

    Optional<NotificationRule> update(UUID tenantId, UUID id, @Nullable String slackWebhookUrl,
                                      @Nullable String teamsWebhookUrl, List<String> emailAddresses, Instant at);

    Optional<NotificationRule> setEnabled(UUID tenantId, UUID id, boolean enabled, Instant at);

    boolean softDelete(UUID tenantId, UUID id, Instant at);
}
