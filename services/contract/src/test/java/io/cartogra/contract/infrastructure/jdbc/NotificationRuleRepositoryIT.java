package io.cartogra.contract.infrastructure.jdbc;

import io.cartogra.contract.AbstractContractIT;
import io.cartogra.contract.domain.NotificationRule;
import io.cartogra.contract.repository.NotificationRuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationRuleRepositoryIT extends AbstractContractIT {

    @Autowired
    private NotificationRuleRepository repository;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void tenantScopedCrudAndSingleRulePerScope() {
        UUID tenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        NotificationRule rule = rule(UUID.randomUUID(), tenant, serviceId, true,
                "https://hooks.slack.com/services/T001/B001/SECRET",
                "https://example.com/webhooks/TEAMSECRET", List.of("alice@example.com", "bob@example.com"), now);

        repository.insert(rule);

        NotificationRule found = repository.findLive(tenant, rule.id()).orElseThrow();
        assertThat(found.id()).isEqualTo(rule.id());
        assertThat(found.tenantId()).isEqualTo(tenant);
        assertThat(found.serviceId()).isEqualTo(serviceId);
        assertThat(found.enabled()).isTrue();
        assertThat(found.slackWebhookUrl()).isEqualTo(rule.slackWebhookUrl());
        assertThat(found.teamsWebhookUrl()).isEqualTo(rule.teamsWebhookUrl());
        assertThat(found.emailAddresses()).containsExactlyElementsOf(rule.emailAddresses());
        assertThat(repository.findLive(otherTenant, rule.id())).isEmpty();
        assertThat(repository.listLive(tenant, 20, 0)).extracting(NotificationRule::id).containsExactly(rule.id());
        assertThat(repository.listLive(otherTenant, 20, 0)).isEmpty();
        assertThat(repository.countLive(tenant)).isEqualTo(1);
        assertThat(repository.countLive(otherTenant)).isZero();
        assertThat(repository.findMatching(tenant, serviceId)).extracting(NotificationRule::id).containsExactly(rule.id());
        assertThat(repository.findMatching(otherTenant, serviceId)).isEmpty();

        NotificationRule secondInScope = rule(UUID.randomUUID(), tenant, serviceId, true, null, null,
                List.of("carol@example.com"), now.plusSeconds(1));
        assertThatThrownBy(() -> repository.insert(secondInScope)).isInstanceOf(DuplicateKeyException.class);
        assertThat(repository.countLive(tenant)).isEqualTo(1);

        NotificationRule updated = repository.update(tenant, rule.id(), null, rule.teamsWebhookUrl(),
                List.of("dave@example.com"), now.plusSeconds(2)).orElseThrow();
        assertThat(updated.slackWebhookUrl()).isNull();
        assertThat(updated.emailAddresses()).containsExactly("dave@example.com");
        assertThat(repository.update(otherTenant, rule.id(), null, null, List.of(), now)).isEmpty();

        NotificationRule disabled = repository.setEnabled(tenant, rule.id(), false, now.plusSeconds(3)).orElseThrow();
        assertThat(disabled.enabled()).isFalse();
        assertThat(repository.setEnabled(otherTenant, rule.id(), true, now)).isEmpty();

        assertThat(repository.softDelete(otherTenant, rule.id(), now)).isFalse();
        assertThat(repository.softDelete(tenant, rule.id(), now.plusSeconds(4))).isTrue();
        assertThat(repository.findLive(tenant, rule.id())).isEmpty();
        assertThat(repository.countLive(tenant)).isZero();
        assertThat(repository.findMatching(tenant, serviceId)).isEmpty();

        repository.insert(secondInScope);
        assertThat(repository.findLive(tenant, secondInScope.id())).isPresent();
        assertThat(repository.findLive(tenant, rule.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_rules WHERE tenant_id = :t AND deleted_at IS NULL",
                new MapSqlParameterSource("t", tenant), Long.class)).isEqualTo(1);
    }

    private static NotificationRule rule(UUID id, UUID tenant, UUID serviceId, boolean enabled, String slack,
                                         String teams, List<String> emails, Instant now) {
        return new NotificationRule(id, tenant, serviceId, enabled, slack, teams, emails, now, now, null);
    }
}
