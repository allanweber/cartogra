package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.repository.DependencyFilter;
import io.cartogra.topology.repository.DependencyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcDependencyRepositoryIT extends AbstractTopologyIT {

    private static final UUID TENANT = UUID.randomUUID();

    @Autowired
    private DependencyRepository repository;

    @Test
    void insertAndFindById() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Dependency saved = repository.save(newDependency(source, target, DependencyType.DECLARED, DependencyProtocol.HTTP));

        Optional<Dependency> found = repository.findById(TENANT, saved.id());

        assertThat(found).isPresent();
        assertThat(found.get().sourceServiceId()).isEqualTo(source);
        assertThat(found.get().targetServiceId()).isEqualTo(target);
        assertThat(found.get().type()).isEqualTo(DependencyType.DECLARED);
        assertThat(found.get().protocol()).isEqualTo(DependencyProtocol.HTTP);
        assertThat(found.get().isDeleted()).isFalse();
    }

    @Test
    void softDeleteHidesTheEdgeFromReads() {
        Dependency saved = repository.save(
                newDependency(UUID.randomUUID(), UUID.randomUUID(), DependencyType.DECLARED, DependencyProtocol.HTTP));

        repository.softDelete(TENANT, saved.id());

        assertThat(repository.findById(TENANT, saved.id())).isEmpty();
    }

    @Test
    void softDeleteAllForServiceHidesEdgesOnEitherSideButLeavesOthers() {
        UUID service = UUID.randomUUID();
        Dependency asSource = repository.save(
                newDependency(service, UUID.randomUUID(), DependencyType.DECLARED, DependencyProtocol.HTTP));
        Dependency asTarget = repository.save(
                newDependency(UUID.randomUUID(), service, DependencyType.DECLARED, DependencyProtocol.GRPC));
        Dependency unrelated = repository.save(
                newDependency(UUID.randomUUID(), UUID.randomUUID(), DependencyType.DECLARED, DependencyProtocol.KAFKA));

        repository.softDeleteAllForService(TENANT, service);

        assertThat(repository.findById(TENANT, asSource.id())).isEmpty();
        assertThat(repository.findById(TENANT, asTarget.id())).isEmpty();
        assertThat(repository.findById(TENANT, unrelated.id())).isPresent();
    }

    @Test
    void edgeIdentityIsUniquePerTenantSourceTargetTypeAndProtocol() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        repository.save(newDependency(source, target, DependencyType.OBSERVED, DependencyProtocol.HTTP));

        assertThatThrownBy(() ->
                repository.save(newDependency(source, target, DependencyType.OBSERVED, DependencyProtocol.HTTP)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void edgeIdentityAllowsDistinctProtocolBetweenSameServices() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        repository.save(newDependency(source, target, DependencyType.OBSERVED, DependencyProtocol.HTTP));

        Dependency other = repository.save(newDependency(source, target, DependencyType.OBSERVED, DependencyProtocol.GRPC));

        assertThat(other.id()).isNotNull();
    }

    @Test
    void softDeletedEdgeDoesNotBlockReusingItsIdentity() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Dependency first = repository.save(newDependency(source, target, DependencyType.OBSERVED, DependencyProtocol.HTTP));
        repository.softDelete(TENANT, first.id());

        Dependency second = repository.save(newDependency(source, target, DependencyType.OBSERVED, DependencyProtocol.HTTP));

        assertThat(repository.findByEdgeIdentity(TENANT, source, target, DependencyType.OBSERVED, DependencyProtocol.HTTP))
                .contains(second);
    }

    @Test
    void findByEdgeIdentityReturnsOnlyTheMatchingActiveEdge() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Dependency saved = repository.save(newDependency(source, target, DependencyType.DECLARED, DependencyProtocol.DB));

        assertThat(repository.findByEdgeIdentity(TENANT, source, target, DependencyType.DECLARED, DependencyProtocol.DB))
                .contains(saved);
        assertThat(repository.findByEdgeIdentity(TENANT, source, target, DependencyType.OBSERVED, DependencyProtocol.DB))
                .isEmpty();
    }

    @Test
    void findByServiceReturnsEdgesOnEitherSide() {
        UUID service = UUID.randomUUID();
        Dependency asSource = repository.save(
                newDependency(service, UUID.randomUUID(), DependencyType.DECLARED, DependencyProtocol.HTTP));
        Dependency asTarget = repository.save(
                newDependency(UUID.randomUUID(), service, DependencyType.DECLARED, DependencyProtocol.GRPC));

        List<Dependency> found = repository.findByService(TENANT, service);

        assertThat(found).extracting(Dependency::id).containsExactlyInAnyOrder(asSource.id(), asTarget.id());
    }

    @Test
    void findAllAndCountAreScopedToTenantAndFilter() {
        // Isolated tenant — findAll/count assert exact contents, so it can't share TENANT
        // with the rest of this class's tests.
        UUID tenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        Dependency inTenant = repository.save(new Dependency(UUID.randomUUID(), tenant, UUID.randomUUID(), UUID.randomUUID(),
                DependencyType.DECLARED, DependencyProtocol.HTTP, null, Instant.now(), Instant.now(), null));
        repository.save(new Dependency(UUID.randomUUID(), otherTenant, UUID.randomUUID(), UUID.randomUUID(),
                DependencyType.DECLARED, DependencyProtocol.HTTP, null, Instant.now(), Instant.now(), null));

        List<Dependency> all = repository.findAll(tenant, DependencyFilter.empty(), 100, 0);
        long count = repository.count(tenant, DependencyFilter.empty());

        assertThat(all).extracting(Dependency::id).containsExactly(inTenant.id());
        assertThat(count).isEqualTo(1);

        assertThat(repository.findAll(tenant, new DependencyFilter(null, DependencyType.OBSERVED, null), 100, 0))
                .isEmpty();
    }

    private Dependency newDependency(UUID source, UUID target, DependencyType type, DependencyProtocol protocol) {
        Instant now = Instant.now();
        return new Dependency(UUID.randomUUID(), TENANT, source, target, type, protocol, null, now, now, null);
    }
}
