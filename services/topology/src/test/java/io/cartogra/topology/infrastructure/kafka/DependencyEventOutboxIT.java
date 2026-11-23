package io.cartogra.topology.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.common.identity.Actor;
import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.api.dto.DeclareDependencyRequest;
import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyService;
import io.cartogra.topology.domain.GraphNodeService;
import io.cartogra.topology.domain.ObservedDependencyService;
import io.cartogra.topology.domain.SpanKind;
import io.cartogra.topology.domain.SpanObservation;
import io.cartogra.topology.domain.event.DependencyChangedPayload;
import io.cartogra.topology.domain.event.ServiceLifecyclePayload;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.GraphNodeUpsert;
import io.cartogra.web.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every write to {@code dependencies} leaves its {@code dependency.*} events in the outbox, in the same transaction. */
@WithMockUser(roles = "ADMIN")
class DependencyEventOutboxIT extends AbstractTopologyIT {

    private static final TypeReference<EventEnvelope<DependencyChangedPayload>> ENVELOPE = new TypeReference<>() {
    };

    @Autowired DependencyService dependencyService;
    @Autowired ObservedDependencyService observedDependencyService;
    @Autowired GraphNodeService graphNodeService;
    @Autowired GraphNodeRepository graphNodeRepository;
    @Autowired OutboxWriter outboxWriter;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    private final UUID tenant = UUID.randomUUID();
    private final Actor admin = Actor.admin(UUID.randomUUID());
    private final UUID checkout = UUID.randomUUID();
    private final UUID payments = UUID.randomUUID();
    private final UUID ledger = UUID.randomUUID();

    @BeforeEach
    void nodes() {
        node(checkout, "checkout-" + checkout);
        node(payments, "payments-" + payments);
        node(ledger, "ledger-" + ledger);
    }

    @Test
    void createWritesDeclaredKeyedByEdgeId() {
        Dependency edge = declare(checkout, payments, DependencyProtocol.HTTP);

        List<Row> rows = rows();
        assertThat(rows).hasSize(1);
        Row row = rows.getFirst();
        assertThat(row.topic()).isEqualTo(DependencyEventPublisher.TOPIC);
        assertThat(row.key()).isEqualTo(edge.id().toString());
        assertThat(row.envelope().eventType()).isEqualTo("dependency.declared");
        assertThat(row.envelope().tenantId()).isEqualTo(tenant);
        assertThat(row.envelope().payload()).isEqualTo(new DependencyChangedPayload(
                edge.id(), checkout, payments, "declared", "http"));
    }

    @Test
    void updateOfTheEdgeIdentityWritesDeclaredAgainWithTheNewValues() {
        Dependency edge = declare(checkout, payments, DependencyProtocol.HTTP);

        dependencyService.update(tenant, admin, edge.id(),
                new DeclareDependencyRequest(checkout, ledger, DependencyProtocol.GRPC, null));

        assertThat(rows()).extracting(r -> r.envelope().payload())
                .containsExactly(
                        new DependencyChangedPayload(edge.id(), checkout, payments, "declared", "http"),
                        new DependencyChangedPayload(edge.id(), checkout, ledger, "declared", "grpc"));
    }

    @Test
    void updateOfTheMetadataOnlyWritesNothing() {
        Dependency edge = declare(checkout, payments, DependencyProtocol.HTTP);

        dependencyService.update(tenant, admin, edge.id(),
                new DeclareDependencyRequest(checkout, payments, DependencyProtocol.HTTP, "{\"note\":1}"));

        assertThat(rows()).hasSize(1);
    }

    @Test
    void deleteWritesRemoved() {
        Dependency edge = declare(checkout, payments, DependencyProtocol.HTTP);

        dependencyService.delete(tenant, admin, edge.id());

        assertThat(rows()).extracting(r -> r.envelope().eventType())
                .containsExactly("dependency.declared", "dependency.removed");
        assertThat(rows().getLast().key()).isEqualTo(edge.id().toString());
    }

    @Test
    void observedInsertWritesObservedAndARefreshWritesNothing() {
        observe(Instant.now().minusSeconds(600));
        observe(Instant.now());

        List<Row> rows = rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().envelope().eventType()).isEqualTo("dependency.observed");
        assertThat(rows.getFirst().envelope().payload().dependencyType()).isEqualTo("observed");
    }

    @Test
    void acceptingAnObservedPairWritesDeclaredForEachNewProtocolAndRemovingItWritesRemoved() {
        observe(Instant.now());

        dependencyService.declareObservedPair(tenant, checkout, payments);
        dependencyService.removeDeclaredPair(tenant, checkout, payments);

        assertThat(rows()).extracting(r -> r.envelope().eventType())
                .containsExactly("dependency.observed", "dependency.declared", "dependency.removed");
    }

    @Test
    void serviceDeletedWritesRemovedForEveryEdgeOfTheService() {
        Dependency in = declare(checkout, payments, DependencyProtocol.HTTP);
        Dependency out = declare(payments, ledger, DependencyProtocol.HTTP);
        Dependency other = declare(checkout, ledger, DependencyProtocol.HTTP);

        graphNodeService.applyLifecycleEvent(EventEnvelope.of("service.deleted", payments, tenant, 1,
                new ServiceLifecyclePayload(payments, tenant, "payments", null, null, "HEALTHY", Instant.now())));

        assertThat(rows().stream().filter(r -> r.envelope().eventType().equals("dependency.removed")))
                .extracting(Row::key)
                .containsExactlyInAnyOrder(in.id().toString(), out.id().toString())
                .doesNotContain(other.id().toString());
    }

    @Test
    void aRolledBackWriteLeavesNoEvent() {
        var tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(_ -> {
            outboxWriter.publish(DependencyEventPublisher.TOPIC, EventEnvelope.of("dependency.declared",
                    UUID.randomUUID(), tenant, 1, "x"));
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(rows()).isEmpty();
    }

    private Dependency declare(UUID source, UUID target, DependencyProtocol protocol) {
        return dependencyService.create(tenant, admin, new DeclareDependencyRequest(source, target, protocol, null));
    }

    private void observe(Instant at) {
        observedDependencyService.ingest(tenant, List.of(new SpanObservation("checkout-" + checkout, SpanKind.CLIENT,
                "trace-" + at.toEpochMilli(), "s1", null, "payments-" + payments, null, DependencyProtocol.HTTP,
                null, null)));
        jdbc.update("UPDATE dependencies SET last_seen_at = ? WHERE tenant_id = ? AND dependency_type = 'observed'",
                java.sql.Timestamp.from(at), tenant);
    }

    private void node(UUID id, String name) {
        graphNodeRepository.upsert(new GraphNodeUpsert(tenant, id, name, null, null, "HEALTHY"));
    }

    private List<Row> rows() {
        return jdbc.query("SELECT topic, record_key, payload FROM outbox WHERE tenant_id = ? ORDER BY id",
                (rs, _) -> new Row(rs.getString("topic"), rs.getString("record_key"),
                        objectMapper.readValue(rs.getString("payload"), ENVELOPE)), tenant);
    }

    private record Row(String topic, String key, EventEnvelope<DependencyChangedPayload> envelope) {
    }
}
