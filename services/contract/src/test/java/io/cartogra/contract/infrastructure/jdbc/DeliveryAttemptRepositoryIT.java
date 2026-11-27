package io.cartogra.contract.infrastructure.jdbc;

import io.cartogra.contract.AbstractContractIT;
import io.cartogra.contract.domain.DeliveryAttempt;
import io.cartogra.contract.repository.DeliveryAttemptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryAttemptRepositoryIT extends AbstractContractIT {

    @Autowired
    private DeliveryAttemptRepository repository;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void pendingRowIsUpdatedAfterTheCall() {
        UUID tenant = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("INSERT INTO notification_rules (id, tenant_id) VALUES (:id, :t)",
                new MapSqlParameterSource("id", ruleId).addValue("t", tenant));
        DeliveryAttempt pending = new DeliveryAttempt(UUID.randomUUID(), tenant, ruleId, null, 2,
                DeliveryAttempt.TEAMS, "https://example.com/webhooks/***", DeliveryAttempt.SECOND_WAIT_MS,
                null, null, DeliveryAttempt.PENDING, createdAt, null);

        repository.insertPending(pending);

        DeliveryAttempt beforeCall = repository.listByRule(tenant, ruleId, 50, 0).getFirst();
        assertThat(beforeCall.id()).isEqualTo(pending.id());
        assertThat(beforeCall.tenantId()).isEqualTo(tenant);
        assertThat(beforeCall.ruleId()).isEqualTo(ruleId);
        assertThat(beforeCall.checkId()).isNull();
        assertThat(beforeCall.attempt()).isEqualTo(2);
        assertThat(beforeCall.channel()).isEqualTo(DeliveryAttempt.TEAMS);
        assertThat(beforeCall.destinationHint()).isEqualTo(pending.destinationHint());
        assertThat(beforeCall.waitMs()).isEqualTo(1000);
        assertThat(beforeCall.statusCode()).isNull();
        assertThat(beforeCall.errorCode()).isNull();
        assertThat(beforeCall.outcome()).isEqualTo(DeliveryAttempt.PENDING);
        assertThat(beforeCall.finishedAt()).isNull();
        assertThat(repository.countByRule(tenant, ruleId)).isEqualTo(1);

        Instant finishedAt = createdAt.plusMillis(120);
        repository.finish(tenant, pending.id(), 503, "PROVIDER_ERROR", DeliveryAttempt.FAILED, finishedAt);

        DeliveryAttempt afterCall = repository.listByRule(tenant, ruleId, 50, 0).getFirst();
        assertThat(afterCall.statusCode()).isEqualTo(503);
        assertThat(afterCall.errorCode()).isEqualTo("PROVIDER_ERROR");
        assertThat(afterCall.outcome()).isEqualTo(DeliveryAttempt.FAILED);
        assertThat(afterCall.finishedAt()).isEqualTo(finishedAt);
        assertThat(afterCall.createdAt()).isEqualTo(createdAt);
        assertThat(repository.countByRule(tenant, ruleId)).isEqualTo(1);

        Long finishedRows = jdbc.queryForObject("""
                SELECT count(*) FROM notification_log
                WHERE tenant_id = :t AND id = :id AND finished_at IS NOT NULL AND outcome = 'failed'
                """, new MapSqlParameterSource("t", tenant).addValue("id", pending.id()), Long.class);
        assertThat(finishedRows).isEqualTo(1);
    }

    @Test
    void attemptCannotNameARuleFromAnotherTenant() {
        UUID tenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_rules (id, tenant_id) VALUES (:id, :t)",
                new MapSqlParameterSource("id", ruleId).addValue("t", otherTenant));

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO notification_log (tenant_id, rule_id, attempt, channel, outcome)
                VALUES (:t, :r, 1, 'slack', 'pending')
                """, new MapSqlParameterSource("t", tenant).addValue("r", ruleId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
