package io.cartogra.topology.api.dto;

import io.cartogra.topology.domain.ServiceDependencies;

import java.util.List;

public record ServiceDependenciesResponse(List<DependencyDirectionEntry> upstream, List<DependencyDirectionEntry> downstream) {
    public static ServiceDependenciesResponse from(ServiceDependencies dependencies) {
        return new ServiceDependenciesResponse(
                dependencies.upstream().stream().map(DependencyDirectionEntry::from).toList(),
                dependencies.downstream().stream().map(DependencyDirectionEntry::from).toList());
    }
}
