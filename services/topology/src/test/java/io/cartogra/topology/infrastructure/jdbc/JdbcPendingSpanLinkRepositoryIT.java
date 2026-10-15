package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.domain.SpanKind;
import io.cartogra.topology.repository.PendingSpanLink;
import io.cartogra.topology.repository.PendingSpanLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcPendingSpanLinkRepositoryIT extends AbstractTopologyIT {

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired
    PendingSpanLinkRepository repository;

    @Autowired
    TransactionTemplate transactions;

    private PendingSpanLink link(UUID tenant, String trace, String span, String parent, SpanKind kind,
                                 Instant expiresAt) {
        return new PendingSpanLink(UUID.randomUUID(), tenant, trace, span, parent, kind, "svc", "http", expiresAt);
    }

    @Test
    void findsClientBySpanIdAndServerByParentSpanId() {
        String trace = UUID.randomUUID().toString();
        repository.insert(link(TENANT, trace, "c1", null, SpanKind.CLIENT, Instant.now().plusSeconds(60)));
        repository.insert(link(TENANT, trace, "s1", "c2", SpanKind.SERVER, Instant.now().plusSeconds(60)));

        assertThat(repository.findClientPartner(TENANT, trace, "c1")).isPresent();
        assertThat(repository.findServerPartner(TENANT, trace, "c2")).isPresent();
        assertThat(repository.findClientPartner(TENANT, trace, "c2")).isEmpty();
        assertThat(repository.findServerPartner(TENANT, trace, "c1")).isEmpty();
    }

    @Test
    void lookupsAreTenantAndTraceScoped() {
        String trace = UUID.randomUUID().toString();
        repository.insert(link(TENANT, trace, "c1", null, SpanKind.CLIENT, Instant.now().plusSeconds(60)));

        assertThat(repository.findClientPartner(UUID.randomUUID(), trace, "c1")).isEmpty();
        assertThat(repository.findClientPartner(TENANT, "other-trace", "c1")).isEmpty();
    }

    @Test
    void deleteRemovesRow() {
        String trace = UUID.randomUUID().toString();
        repository.insert(link(TENANT, trace, "c1", null, SpanKind.CLIENT, Instant.now().plusSeconds(60)));
        PendingSpanLink found = repository.findClientPartner(TENANT, trace, "c1").orElseThrow();

        repository.delete(found.id());

        assertThat(repository.findClientPartner(TENANT, trace, "c1")).isEmpty();
    }

    @Test
    void deleteExpiredRemovesOnlyExpiredRows() {
        UUID tenant = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        repository.insert(link(tenant, trace, "old", null, SpanKind.CLIENT, Instant.now().minus(Duration.ofMinutes(1))));
        repository.insert(link(tenant, trace, "fresh", null, SpanKind.CLIENT, Instant.now().plus(Duration.ofMinutes(5))));

        assertThat(repository.deleteExpired(Instant.now())).isGreaterThanOrEqualTo(1);

        assertThat(repository.findClientPartner(tenant, trace, "fresh")).isPresent();
    }

    @Test
    void lockTenantIsReentrantWithinATransaction() {
        transactions.executeWithoutResult(_ -> {
            repository.lockTenant(TENANT);
            repository.lockTenant(TENANT);
        });
    }
}
