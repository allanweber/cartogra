package io.cartogra.ingestion.infrastructure.jdbc;

import io.cartogra.ingestion.domain.Cluster;
import io.cartogra.ingestion.domain.ClusterSource;
import io.cartogra.ingestion.domain.ClusterStatus;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandConsumer;
import io.cartogra.ingestion.infrastructure.kafka.SyncResultProducer;
import io.cartogra.ingestion.repository.ClusterRepository;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.kafka.bootstrap-servers=localhost:9092")
class ClusterRepositoryIT {

    @MockitoBean
    SyncResultProducer syncResultProducer;

    @MockitoBean
    SyncCommandConsumer syncCommandConsumer;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresTestSupport.POSTGRES.start();
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }

    @Autowired
    ClusterRepository repository;

    @Test
    void save_andFindById_roundtrip() {
        UUID tenantId = UUID.randomUUID();
        Cluster cluster = newCluster(tenantId);

        Cluster saved = repository.save(cluster);
        Optional<Cluster> found = repository.findById(tenantId, saved.id());

        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("prod");
        assertThat(found.get().source()).isEqualTo(ClusterSource.MANUAL);
        assertThat(found.get().apiServerUrl()).isEqualTo("https://1.2.3.4:6443");
        assertThat(found.get().status()).isEqualTo(ClusterStatus.CONNECTING);
        assertThat(found.get().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void findById_wrongTenant_returnsEmpty() {
        UUID tenantId = UUID.randomUUID();
        Cluster saved = repository.save(newCluster(tenantId));

        Optional<Cluster> found = repository.findById(UUID.randomUUID(), saved.id());

        assertThat(found).isEmpty();
    }

    @Test
    void findAll_returnsTenantClusters() {
        UUID tenantId = UUID.randomUUID();
        repository.save(newCluster(tenantId, "a"));
        repository.save(newCluster(tenantId, "b"));
        repository.save(newCluster(UUID.randomUUID(), "other-tenant"));

        List<Cluster> results = repository.findAll(tenantId, 20, 0);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(Cluster::name).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void count_excludesOtherTenants() {
        UUID tenantId = UUID.randomUUID();
        repository.save(newCluster(tenantId));
        repository.save(newCluster(tenantId));
        repository.save(newCluster(UUID.randomUUID()));

        assertThat(repository.count(tenantId)).isEqualTo(2);
    }

    @Test
    void softDelete_removesFromQueries() {
        UUID tenantId = UUID.randomUUID();
        Cluster saved = repository.save(newCluster(tenantId));

        repository.softDelete(tenantId, saved.id());

        assertThat(repository.findById(tenantId, saved.id())).isEmpty();
        assertThat(repository.findAll(tenantId, 20, 0)).noneMatch(c -> c.id().equals(saved.id()));
        assertThat(repository.count(tenantId)).isEqualTo(0);
    }

    @Test
    void updateStatus_changesStatusAndMessage() {
        UUID tenantId = UUID.randomUUID();
        Cluster saved = repository.save(newCluster(tenantId));

        repository.updateStatus(saved.id(), ClusterStatus.ACTIVE, null);
        Cluster updated = repository.findById(tenantId, saved.id()).orElseThrow();

        assertThat(updated.status()).isEqualTo(ClusterStatus.ACTIVE);
        assertThat(updated.statusMessage()).isNull();
    }

    @Test
    void updateStatus_error_storesMessage() {
        UUID tenantId = UUID.randomUUID();
        Cluster saved = repository.save(newCluster(tenantId));

        repository.updateStatus(saved.id(), ClusterStatus.ERROR, "connection refused");
        Cluster updated = repository.findById(tenantId, saved.id()).orElseThrow();

        assertThat(updated.status()).isEqualTo(ClusterStatus.ERROR);
        assertThat(updated.statusMessage()).isEqualTo("connection refused");
    }

    @Test
    void findAllActive_excludesDeleted() {
        UUID tenantId = UUID.randomUUID();
        Cluster kept = repository.save(newCluster(tenantId, "kept"));
        Cluster deleted = repository.save(newCluster(tenantId, "deleted"));
        repository.softDelete(tenantId, deleted.id());

        List<Cluster> active = repository.findAllActive();

        assertThat(active).extracting(Cluster::id).contains(kept.id());
        assertThat(active).extracting(Cluster::id).doesNotContain(deleted.id());
    }

    @Test
    void save_withEncryptedCredentials_persists() {
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();
        Cluster cluster = new Cluster(UUID.randomUUID(), tenantId, "secure", ClusterSource.KUBECONFIG,
                "https://1.2.3.4:6443", "encrypted-ca", "encrypted-token", false,
                ClusterStatus.CONNECTING, null, now, now, null, null);

        Cluster saved = repository.save(cluster);
        Cluster found = repository.findById(tenantId, saved.id()).orElseThrow();

        assertThat(found.caCertPem()).isEqualTo("encrypted-ca");
        assertThat(found.saToken()).isEqualTo("encrypted-token");
    }

    private static Cluster newCluster(UUID tenantId) {
        return newCluster(tenantId, "prod");
    }

    private static Cluster newCluster(UUID tenantId, String name) {
        Instant now = Instant.now();
        return new Cluster(UUID.randomUUID(), tenantId, name, ClusterSource.MANUAL,
                "https://1.2.3.4:6443", null, null, false,
                ClusterStatus.CONNECTING, null, now, now, null, null);
    }
}
