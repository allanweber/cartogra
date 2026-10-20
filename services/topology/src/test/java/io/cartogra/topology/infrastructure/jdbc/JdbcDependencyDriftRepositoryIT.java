package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.domain.DependencyDrift;
import io.cartogra.topology.domain.DriftResolution;
import io.cartogra.topology.domain.DriftType;
import io.cartogra.topology.repository.DependencyDriftRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcDependencyDriftRepositoryIT extends AbstractTopologyIT {

    @Autowired
    private DependencyDriftRepository repository;

    @Test
    void insertAndFindById() {
        UUID tenant = UUID.randomUUID();
        DependencyDrift saved = repository.save(newDrift(tenant, UUID.randomUUID(), UUID.randomUUID(), DriftType.UNDECLARED));

        Optional<DependencyDrift> found = repository.findById(tenant, saved.id());

        assertThat(found).isPresent();
        assertThat(found.get().type()).isEqualTo(DriftType.UNDECLARED);
        assertThat(found.get().isResolved()).isFalse();
    }

    @Test
    void resolveRemovesItFromTheActiveList() {
        UUID tenant = UUID.randomUUID();
        DependencyDrift saved = repository.save(newDrift(tenant, UUID.randomUUID(), UUID.randomUUID(), DriftType.MISSING));
        UUID resolvedBy = UUID.randomUUID();

        repository.resolve(tenant, saved.id(), resolvedBy, DriftResolution.DISMISSED, Instant.now());

        Optional<DependencyDrift> found = repository.findById(tenant, saved.id());
        assertThat(found).isPresent();
        assertThat(found.get().isResolved()).isTrue();
        assertThat(found.get().resolvedBy()).isEqualTo(resolvedBy);
        assertThat(repository.findActive(tenant, 50, 0)).noneMatch(d -> d.id().equals(saved.id()));
        assertThat(repository.countActive(tenant)).isZero();
    }

    @Test
    void resolvingAlreadyResolvedDriftIsANoop() {
        UUID tenant = UUID.randomUUID();
        DependencyDrift saved = repository.save(newDrift(tenant, UUID.randomUUID(), UUID.randomUUID(), DriftType.MISSING));
        Instant firstResolution = Instant.now();
        UUID firstResolver = UUID.randomUUID();
        repository.resolve(tenant, saved.id(), firstResolver, DriftResolution.DISMISSED, firstResolution);

        repository.resolve(tenant, saved.id(), UUID.randomUUID(), DriftResolution.DISMISSED, Instant.now());

        DependencyDrift found = repository.findById(tenant, saved.id()).orElseThrow();
        assertThat(found.resolvedBy()).isEqualTo(firstResolver);
    }

    @Test
    void findActiveByEdgeReturnsOnlyTheUnresolvedMatch() {
        UUID tenant = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        DependencyDrift saved = repository.save(newDrift(tenant, source, target, DriftType.UNDECLARED));

        assertThat(repository.findActiveByEdge(tenant, source, target, DriftType.UNDECLARED)).contains(saved);
        assertThat(repository.findActiveByEdge(tenant, source, target, DriftType.MISSING)).isEmpty();

        repository.resolve(tenant, saved.id(), UUID.randomUUID(), DriftResolution.DISMISSED, Instant.now());

        assertThat(repository.findActiveByEdge(tenant, source, target, DriftType.UNDECLARED)).isEmpty();
    }

    @Test
    void activeDriftIsUniquePerTenantEdgeAndType() {
        UUID tenant = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        repository.save(newDrift(tenant, source, target, DriftType.UNDECLARED));

        assertThatThrownBy(() -> repository.save(newDrift(tenant, source, target, DriftType.UNDECLARED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void resolvedDriftDoesNotBlockReDetectingTheSameEdgeAndType() {
        UUID tenant = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        DependencyDrift first = repository.save(newDrift(tenant, source, target, DriftType.UNDECLARED));
        repository.resolve(tenant, first.id(), UUID.randomUUID(), DriftResolution.DISMISSED, Instant.now());

        DependencyDrift redetected = repository.save(newDrift(tenant, source, target, DriftType.UNDECLARED));

        assertThat(redetected.id()).isNotEqualTo(first.id());
        assertThat(repository.findActiveByEdge(tenant, source, target, DriftType.UNDECLARED)).contains(redetected);
    }

    @Test
    void findActiveOrdersByDetectedAtDescending() {
        UUID tenant = UUID.randomUUID();
        DependencyDrift older = repository.save(
                newDrift(tenant, UUID.randomUUID(), UUID.randomUUID(), DriftType.UNDECLARED, Instant.now().minusSeconds(60)));
        DependencyDrift newer = repository.save(
                newDrift(tenant, UUID.randomUUID(), UUID.randomUUID(), DriftType.MISSING, Instant.now()));

        List<DependencyDrift> active = repository.findActive(tenant, 50, 0);

        assertThat(active).extracting(DependencyDrift::id).containsExactly(newer.id(), older.id());
    }

    @Test
    void resolveReportsWhetherItClosedTheRecord() {
        UUID tenant = UUID.randomUUID();
        DependencyDrift saved = repository.save(newDrift(tenant, UUID.randomUUID(), UUID.randomUUID(), DriftType.MISSING));

        assertThat(repository.resolve(tenant, saved.id(), null, DriftResolution.AUTO, Instant.now())).isTrue();
        assertThat(repository.resolve(tenant, saved.id(), null, DriftResolution.AUTO, Instant.now())).isFalse();
        assertThat(repository.findById(tenant, saved.id()).orElseThrow().resolution()).isEqualTo(DriftResolution.AUTO);
    }

    @Test
    void insertIfAbsentSkipsAnEdgeThatAlreadyHasActiveDrift() {
        UUID tenant = UUID.randomUUID();
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        assertThat(repository.insertIfAbsent(newDrift(tenant, source, target, DriftType.UNDECLARED))).isTrue();
        assertThat(repository.insertIfAbsent(newDrift(tenant, source, target, DriftType.UNDECLARED))).isFalse();
        assertThat(repository.insertIfAbsent(newDrift(tenant, source, target, DriftType.MISSING))).isTrue();
        assertThat(repository.findAllActive(tenant)).hasSize(2);
    }

    @Test
    void findDismissedReturnsOnlyDismissedRecords() {
        UUID tenant = UUID.randomUUID();
        DependencyDrift dismissed = repository.save(newDrift(tenant, UUID.randomUUID(), UUID.randomUUID(), DriftType.UNDECLARED));
        DependencyDrift auto = repository.save(newDrift(tenant, UUID.randomUUID(), UUID.randomUUID(), DriftType.UNDECLARED));
        repository.resolve(tenant, dismissed.id(), UUID.randomUUID(), DriftResolution.DISMISSED, Instant.now());
        repository.resolve(tenant, auto.id(), null, DriftResolution.AUTO, Instant.now());

        assertThat(repository.findDismissed(tenant)).extracting(DependencyDrift::id).containsExactly(dismissed.id());
    }

    @Test
    void tenantsToSweepIncludeThoseWithActiveDrift() {
        UUID tenant = UUID.randomUUID();
        repository.save(newDrift(tenant, UUID.randomUUID(), UUID.randomUUID(), DriftType.MISSING));

        assertThat(repository.findTenantIdsToSweep()).contains(tenant);
    }

    private DependencyDrift newDrift(UUID tenant, UUID source, UUID target, DriftType type) {
        return newDrift(tenant, source, target, type, Instant.now());
    }

    private DependencyDrift newDrift(UUID tenant, UUID source, UUID target, DriftType type, Instant detectedAt) {
        return new DependencyDrift(UUID.randomUUID(), tenant, source, target, type, detectedAt, null, null);
    }
}
