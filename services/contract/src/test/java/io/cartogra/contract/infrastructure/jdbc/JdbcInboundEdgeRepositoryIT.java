package io.cartogra.contract.infrastructure.jdbc;

import io.cartogra.contract.AbstractContractIT;
import io.cartogra.contract.domain.Consumer;
import io.cartogra.contract.domain.InboundEdge;
import io.cartogra.contract.repository.InboundEdgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class JdbcInboundEdgeRepositoryIT extends AbstractContractIT {

    private static final List<String> API = List.of("grpc", "http");

    @Autowired InboundEdgeRepository repository;
    @Autowired NamedParameterJdbcTemplate jdbc;

    UUID tenant;
    UUID target;
    Instant now;

    @BeforeEach
    void setUp() {
        tenant = UUID.randomUUID();
        target = UUID.randomUUID();
        now = Instant.now();
    }

    @Test
    void edgesOfOneSourceMergeIntoOneConsumerWithSortedTypesAndProtocols() {
        UUID source = service("billing-service");
        edge(source, "observed", "http");
        edge(source, "declared", "http");
        edge(source, "declared", "grpc");

        assertThat(consumers()).containsExactly(
                new Consumer(source, "billing-service", List.of("declared", "observed"), List.of("grpc", "http")));
        assertThat(repository.countConsumers(tenant, target, API)).isEqualTo(1);
    }

    @Test
    void onlyTheRequestedProtocolsCount() {
        UUID http = service("http-caller");
        UUID kafka = service("kafka-caller");
        edge(http, "declared", "http");
        edge(kafka, "declared", "kafka");

        assertThat(consumers()).extracting(Consumer::serviceId).containsExactly(http);
        assertThat(repository.countConsumers(tenant, target, API)).isEqualTo(1);
        assertThat(repository.findConsumers(tenant, target, List.of(), 50, 0)).isEmpty();
        assertThat(repository.countConsumers(tenant, target, List.of())).isZero();
    }

    @Test
    void selfEdgesTombstonedServicesAndRemovedEdgesAreLeftOut() {
        service(target, "producer");
        edge(target, "declared", "http");
        UUID tombstoned = service("gone-service");
        edge(tombstoned, "declared", "http");
        jdbc.update("UPDATE services SET deleted_at = now() WHERE tenant_id = :t AND service_id = :s",
                new MapSqlParameterSource("t", tenant).addValue("s", tombstoned));
        UUID removed = service("removed-caller");
        InboundEdge removedEdge = edge(removed, "declared", "http");
        repository.markRemoved(tenant, removedEdge, now);

        assertThat(consumers()).isEmpty();
        assertThat(repository.countConsumers(tenant, target, API)).isZero();
    }

    @Test
    void aRemovedEdgeStaysRemovedWhenItIsUpsertedAgain() {
        UUID source = service("late-caller");
        InboundEdge removedFirst = new InboundEdge(UUID.randomUUID(), source, target, "declared", "http");
        repository.markRemoved(tenant, removedFirst, now);
        repository.upsert(tenant, removedFirst, now.plusSeconds(1));

        assertThat(consumers()).isEmpty();
    }

    @Test
    void aSourceWithNoProjectedServiceIsListedWithoutANameAfterTheNamedOnes() {
        UUID unknown = UUID.randomUUID();
        UUID named = service("zeta-service");
        edge(unknown, "observed", "http");
        edge(named, "declared", "http");

        assertThat(consumers()).extracting(Consumer::serviceId, Consumer::serviceName)
                .containsExactly(
                        tuple(named, "zeta-service"),
                        tuple(unknown, null));
    }

    @Test
    void pagesAreSortedByNameAndTheCountIsTheTotal() {
        UUID charlie = service("charlie");
        UUID alpha = service("alpha");
        UUID bravo = service("bravo");
        edge(charlie, "declared", "http");
        edge(alpha, "declared", "http");
        edge(bravo, "declared", "http");

        assertThat(repository.findConsumers(tenant, target, API, 2, 0)).extracting(Consumer::serviceId)
                .containsExactly(alpha, bravo);
        assertThat(repository.findConsumers(tenant, target, API, 2, 2)).extracting(Consumer::serviceId)
                .containsExactly(charlie);
        assertThat(repository.countConsumers(tenant, target, API)).isEqualTo(3);
    }

    @Test
    void anotherTenantSeesNoConsumers() {
        edge(service("billing-service"), "declared", "http");

        UUID otherTenant = UUID.randomUUID();
        assertThat(repository.findConsumers(otherTenant, target, API, 50, 0)).isEmpty();
        assertThat(repository.countConsumers(otherTenant, target, API)).isZero();
    }

    private List<Consumer> consumers() {
        return repository.findConsumers(tenant, target, API, 50, 0);
    }

    private UUID service(String name) {
        return service(UUID.randomUUID(), name);
    }

    private UUID service(UUID id, String name) {
        jdbc.update("INSERT INTO services (tenant_id, service_id, name) VALUES (:t, :s, :n)",
                new MapSqlParameterSource("t", tenant).addValue("s", id).addValue("n", name));
        return id;
    }

    private InboundEdge edge(UUID source, String type, String protocol) {
        InboundEdge edge = new InboundEdge(UUID.randomUUID(), source, target, type, protocol);
        repository.upsert(tenant, edge, now);
        return edge;
    }
}
