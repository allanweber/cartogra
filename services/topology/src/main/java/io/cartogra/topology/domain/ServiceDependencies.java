package io.cartogra.topology.domain;

import java.util.List;

/** A service's declared dependencies, bucketed by direction relative to that service. */
public record ServiceDependencies(List<DependencyEdge> upstream, List<DependencyEdge> downstream) {}
