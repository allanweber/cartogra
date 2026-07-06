package io.cartogra.ingestion.api.dto;

import io.cartogra.ingestion.domain.ClusterSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

public record KubernetesClusterRequest(
        @NotBlank String name,
        @NotNull ClusterSource source,
        @Nullable String kubeconfig,
        @Nullable String apiServerUrl,
        @Nullable String caCertPem,
        @Nullable String saToken,
        @Nullable Boolean skipTlsVerify
) {}
