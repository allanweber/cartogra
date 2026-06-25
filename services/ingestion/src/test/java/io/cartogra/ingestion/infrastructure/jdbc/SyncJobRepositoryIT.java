package io.cartogra.ingestion.infrastructure.jdbc;

import io.cartogra.ingestion.repository.SyncJobRepository;
import io.cartogra.ingestion.domain.SyncJob;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandConsumer;
import io.cartogra.ingestion.infrastructure.kafka.SyncResultProducer;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ingestion.workers.k8s.enabled=false",
        "spring.kafka.bootstrap-servers=localhost:9092"
})
class SyncJobRepositoryIT {

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
    SyncJobRepository repository;

    @Test
    void save_persistsPendingJob() {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();

        SyncJob saved = repository.save(SyncJob.create(tenantId, connectionId, "github"));

        assertThat(saved.id()).isNotNull();
        assertThat(saved.status()).isEqualTo("PENDING");
        assertThat(saved.tenantId()).isEqualTo(tenantId);
    }

    @Test
    void markRunning_updatesStatus() {
        UUID tenantId = UUID.randomUUID();
        SyncJob job = repository.save(SyncJob.create(tenantId, UUID.randomUUID(), "github"));

        repository.markRunning(job.id());

        Optional<SyncJob> found = repository.findById(tenantId, job.id());
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo("RUNNING");
        assertThat(found.get().startedAt()).isNotNull();
    }

    @Test
    void markCompleted_setsRepoCount() {
        UUID tenantId = UUID.randomUUID();
        SyncJob job = repository.save(SyncJob.create(tenantId, UUID.randomUUID(), "github"));
        repository.markRunning(job.id());

        repository.markCompleted(job.id(), 42);

        Optional<SyncJob> found = repository.findById(tenantId, job.id());
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo("COMPLETED");
        assertThat(found.get().repositoriesSynced()).isEqualTo(42);
        assertThat(found.get().completedAt()).isNotNull();
    }

    @Test
    void markFailed_setsErrorMessage() {
        UUID tenantId = UUID.randomUUID();
        SyncJob job = repository.save(SyncJob.create(tenantId, UUID.randomUUID(), "github"));
        repository.markRunning(job.id());

        repository.markFailed(job.id(), "token expired");

        Optional<SyncJob> found = repository.findById(tenantId, job.id());
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo("FAILED");
        assertThat(found.get().errorMessage()).isEqualTo("token expired");
    }

    @Test
    void findById_withWrongTenantReturnsEmpty() {
        UUID tenantId = UUID.randomUUID();
        SyncJob job = repository.save(SyncJob.create(tenantId, UUID.randomUUID(), "github"));

        Optional<SyncJob> found = repository.findById(UUID.randomUUID(), job.id());
        assertThat(found).isEmpty();
    }
}
