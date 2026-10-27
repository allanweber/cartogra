package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.SpanKind;
import io.cartogra.topology.repository.MessagingEndpoint;
import io.cartogra.topology.repository.PendingSpanLink;
import io.cartogra.topology.repository.PendingSpanLinkRepository;
import io.cartogra.topology.repository.SpanRef;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcPendingSpanLinkRepositoryIT extends AbstractTopologyIT {

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired
    PendingSpanLinkRepository repository;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private PendingSpanLink link(UUID tenant, String trace, String span, String parent, SpanKind kind,
                                 Instant expiresAt) {
        return new PendingSpanLink(UUID.randomUUID(), tenant, trace, span, parent, kind, "svc",
                DependencyProtocol.HTTP, expiresAt);
    }

    private void park(UUID tenant, PendingSpanLink... links) {
        transactions.executeWithoutResult(_ -> repository.park(tenant, List.of(links)));
    }

    private Optional<PendingSpanLink> takeClient(UUID tenant, String trace, String parentSpanId) {
        return transactions.execute(_ -> repository.takeClientPartner(tenant, trace, parentSpanId));
    }

    private Optional<PendingSpanLink> takeServer(UUID tenant, String trace, String clientSpanId) {
        return transactions.execute(_ -> repository.takeServerPartner(tenant, trace, clientSpanId));
    }

    private long rows(UUID tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM pending_span_links WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class);
    }

    @Test
    void takesClientBySpanIdAndServerByParentSpanIdAndKeepsTheProtocol() {
        UUID tenant = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        park(tenant, link(tenant, trace, "c1", null, SpanKind.CLIENT, Instant.now().plusSeconds(60)),
                link(tenant, trace, "s1", "c2", SpanKind.SERVER, Instant.now().plusSeconds(60)));

        assertThat(takeClient(tenant, trace, "c2")).isEmpty();
        assertThat(takeServer(tenant, trace, "c1")).isEmpty();
        assertThat(takeClient(tenant, trace, "c1")).get()
                .extracting(PendingSpanLink::protocol).isEqualTo(DependencyProtocol.HTTP);
        assertThat(takeServer(tenant, trace, "c2")).isPresent();
    }

    @Test
    void takeRemovesTheRow() {
        UUID tenant = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        park(tenant, link(tenant, trace, "c1", null, SpanKind.CLIENT, Instant.now().plusSeconds(60)));

        assertThat(takeClient(tenant, trace, "c1")).isPresent();

        assertThat(takeClient(tenant, trace, "c1")).isEmpty();
        assertThat(rows(tenant)).isZero();
    }

    @Test
    void takesAreTenantAndTraceScopedAndSkipExpiredRows() {
        String trace = UUID.randomUUID().toString();
        park(TENANT, link(TENANT, trace, "c1", null, SpanKind.CLIENT, Instant.now().plusSeconds(60)),
                link(TENANT, trace, "old", null, SpanKind.CLIENT, Instant.now().minusSeconds(1)));

        assertThat(takeClient(UUID.randomUUID(), trace, "c1")).isEmpty();
        assertThat(takeClient(TENANT, "other-trace", "c1")).isEmpty();
        assertThat(takeClient(TENANT, trace, "old")).isEmpty();
    }

    @Test
    void takeWaitsForAConcurrentParkOfTheOtherHalfToCommit() throws Exception {
        UUID tenant = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> clientSide = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(_ -> {
            assertThat(repository.takeServerPartner(tenant, trace, "c1")).isEmpty();
            repository.park(tenant, List.of(link(tenant, trace, "c1", null, SpanKind.CLIENT,
                    Instant.now().plusSeconds(60))));
            parked.countDown();
            await(release);
        }));
        assertThat(parked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Optional<PendingSpanLink>> serverSide = CompletableFuture.supplyAsync(
                () -> transactions.execute(_ -> repository.takeClientPartner(tenant, trace, "c1")));
        Thread.sleep(300);
        assertThat(serverSide).isNotDone();

        release.countDown();
        clientSide.get(10, TimeUnit.SECONDS);
        assertThat(serverSide.get(10, TimeUnit.SECONDS)).isPresent();
        assertThat(rows(tenant)).isZero();
    }

    @Test
    void lockIsTakenOncePerTransactionAndReleasedAfterIt() {
        UUID tenant = UUID.randomUUID();
        transactions.executeWithoutResult(_ -> {
            repository.takeClientPartner(tenant, "t", "a");
            repository.takeServerPartner(tenant, "t", "b");
            repository.park(tenant, List.of(link(tenant, "t", "c", null, SpanKind.CLIENT,
                    Instant.now().plusSeconds(60))));
        });

        assertThat(takeClient(tenant, "t", "c")).isPresent();
    }

    @Test
    void deleteServerPartnersRemovesOnlyMatchingServerRowsOfTheTenant() {
        UUID otherTenant = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plusSeconds(60);
        park(tenant, link(tenant, trace, "s1", "c1", SpanKind.SERVER, expiresAt),
                link(tenant, trace, "s2", "c2", SpanKind.SERVER, expiresAt),
                link(tenant, trace, "c1", null, SpanKind.CLIENT, expiresAt));
        park(otherTenant, link(otherTenant, trace, "s1", "c1", SpanKind.SERVER, expiresAt));

        int deleted = repository.deleteServerPartners(tenant,
                List.of(new SpanRef(trace, "c1"), new SpanRef("other-trace", "c2")));

        assertThat(deleted).isEqualTo(1);
        assertThat(takeServer(tenant, trace, "c1")).isEmpty();
        assertThat(takeServer(tenant, trace, "c2")).isPresent();
        assertThat(takeClient(tenant, trace, "c1")).isPresent();
        assertThat(takeServer(otherTenant, trace, "c1")).isPresent();
    }

    @Test
    void joinMessagingEndpointsReturnsLiveEndpointsOfTheBatchDestinationsCaseInsensitively() {
        UUID tenant = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));
        transactions.executeWithoutResult(_ -> repository.joinMessagingEndpoints(tenant, List.of(
                new MessagingEndpoint("orders", SpanKind.CONSUMER, "Notifications"),
                new MessagingEndpoint("refunds", SpanKind.CONSUMER, "ledger")), expiresAt));

        var live = transactions.execute(_ -> repository.joinMessagingEndpoints(tenant, List.of(
                new MessagingEndpoint("orders", SpanKind.PRODUCER, "payments"),
                new MessagingEndpoint("orders", SpanKind.PRODUCER, "PAYMENTS")), expiresAt));

        assertThat(live).containsExactlyInAnyOrder(
                new MessagingEndpoint("orders", SpanKind.PRODUCER, "payments"),
                new MessagingEndpoint("orders", SpanKind.CONSUMER, "notifications"));
        assertThat(rows(tenant)).isEqualTo(3);
    }

    @Test
    void deleteExpiredRemovesOnlyExpiredRows() {
        UUID tenant = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        park(tenant, link(tenant, trace, "old", null, SpanKind.CLIENT, Instant.now().minus(Duration.ofMinutes(1))),
                link(tenant, trace, "fresh", null, SpanKind.CLIENT, Instant.now().plus(Duration.ofMinutes(5))));

        assertThat(repository.deleteExpired(Instant.now())).isGreaterThanOrEqualTo(1);

        assertThat(rows(tenant)).isEqualTo(1);
        assertThat(takeClient(tenant, trace, "fresh")).isPresent();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
