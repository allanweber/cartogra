package io.cartogra.registry.api.mapper;

import io.cartogra.registry.api.dto.ServiceResponse;
import io.cartogra.registry.api.dto.ServiceSnapshotResponse;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.ServiceSnapshot;

public final class ServiceMapper {

    private ServiceMapper() {}

    public static ServiceResponse toResponse(Service s) {
        return new ServiceResponse(
                s.id(),
                s.tenantId(),
                s.name(),
                s.description(),
                s.teamId(),
                s.repositoryUrl(),
                s.techStack(),
                s.metadata(),
                s.healthStatus().toDbValue(),
                s.lastDeployedAt(),
                s.createdAt(),
                s.updatedAt(),
                s.externalId(),
                s.connectionId(),
                s.source(),
                s.repositoryRef(),
                s.k8sCluster(),
                s.k8sNamespace(),
                s.k8sDeployment(),
                s.healthEndpoint(),
                s.lastCommitAt(),
                s.lastCommitSha()
        );
    }

    public static ServiceSnapshotResponse toSnapshotResponse(ServiceSnapshot snap) {
        return new ServiceSnapshotResponse(
                snap.id(),
                snap.serviceId(),
                snap.tenantId(),
                snap.snapshot(),
                snap.changedBy(),
                snap.changedAt()
        );
    }
}
