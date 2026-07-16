package io.cartogra.ingestion.domain;

import io.cartogra.ingestion.api.dto.KubernetesClusterRequest;
import io.cartogra.ingestion.domain.exception.ClusterNotFoundException;
import io.cartogra.ingestion.infrastructure.k8s.ClusterWorkerManager;
import io.cartogra.ingestion.infrastructure.k8s.CredentialEncryptor;
import io.cartogra.ingestion.infrastructure.k8s.KubeconfigParser;
import io.cartogra.ingestion.infrastructure.registry.RegistryPlanLimitClient;
import io.cartogra.ingestion.repository.ClusterRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClusterServiceTest {

    private final ClusterRepository repository = mock(ClusterRepository.class);
    private final ClusterWorkerManager workerManager = mock(ClusterWorkerManager.class);
    private final CredentialEncryptor encryptor = mock(CredentialEncryptor.class);
    private final KubeconfigParser kubeconfigParser = mock(KubeconfigParser.class);
    private final RegistryPlanLimitClient planLimitClient = mock(RegistryPlanLimitClient.class);
    private final ClusterService service =
            new ClusterService(repository, workerManager, encryptor, kubeconfigParser, planLimitClient);

    private static final UUID TENANT_ID = UUID.randomUUID();

    @Test
    void create_manual_savesAndStartsWorker() {
        when(encryptor.encrypt("my-ca")).thenReturn("enc-ca");
        when(encryptor.encrypt("my-token")).thenReturn("enc-token");
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        var req = new KubernetesClusterRequest("prod", ClusterSource.MANUAL,
                null, "https://1.2.3.4:6443", "my-ca", "my-token", false);

        Cluster result = service.create(TENANT_ID, req);

        assertThat(result.name()).isEqualTo("prod");
        assertThat(result.source()).isEqualTo(ClusterSource.MANUAL);
        assertThat(result.apiServerUrl()).isEqualTo("https://1.2.3.4:6443");
        assertThat(result.caCertPem()).isEqualTo("enc-ca");
        assertThat(result.saToken()).isEqualTo("enc-token");
        assertThat(result.skipTlsVerify()).isFalse();
        assertThat(result.status()).isEqualTo(ClusterStatus.CONNECTING);
        assertThat(result.tenantId()).isEqualTo(TENANT_ID);

        verify(workerManager).start(result);
    }

    @Test
    void create_kubeconfig_parsesAndSaves() {
        var parsed = new KubeconfigParser.ParsedClusterConfig(
                "https://127.0.0.1:6443", "ca-data", "token", false);
        when(kubeconfigParser.parse("kubeconfig-yaml")).thenReturn(parsed);
        when(encryptor.encrypt("ca-data")).thenReturn("enc-ca");
        when(encryptor.encrypt("token")).thenReturn("enc-token");
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        var req = new KubernetesClusterRequest("local", ClusterSource.KUBECONFIG,
                "kubeconfig-yaml", null, null, null, null);

        Cluster result = service.create(TENANT_ID, req);

        assertThat(result.source()).isEqualTo(ClusterSource.KUBECONFIG);
        assertThat(result.apiServerUrl()).isEqualTo("https://127.0.0.1:6443");
        assertThat(result.caCertPem()).isEqualTo("enc-ca");
        assertThat(result.saToken()).isEqualTo("enc-token");
        verify(kubeconfigParser).parse("kubeconfig-yaml");
    }

    @Test
    void create_manual_nullSaToken_throws() {
        var req = new KubernetesClusterRequest("local", ClusterSource.MANUAL,
                null, "https://127.0.0.1:6443", null, null, true);

        assertThatThrownBy(() -> service.create(TENANT_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("saToken is required");

        verify(repository, never()).save(any());
    }

    @Test
    void create_manual_blankSaToken_throws() {
        var req = new KubernetesClusterRequest("local", ClusterSource.MANUAL,
                null, "https://127.0.0.1:6443", null, "  ", true);

        assertThatThrownBy(() -> service.create(TENANT_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("saToken is required");

        verify(repository, never()).save(any());
    }

    @Test
    void create_missingName_throws() {
        var req = new KubernetesClusterRequest("", ClusterSource.MANUAL,
                null, "https://127.0.0.1:6443", null, null, null);

        assertThatThrownBy(() -> service.create(TENANT_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void create_missingSource_throws() {
        var req = new KubernetesClusterRequest("prod", null,
                null, "https://1.2.3.4:6443", null, null, null);

        assertThatThrownBy(() -> service.create(TENANT_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source is required");
    }

    @Test
    void create_kubeconfigWithoutYaml_throws() {
        var req = new KubernetesClusterRequest("local", ClusterSource.KUBECONFIG,
                null, null, null, null, null);

        assertThatThrownBy(() -> service.create(TENANT_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kubeconfig is required");
    }

    @Test
    void create_manualWithoutApiServerUrl_throws() {
        var req = new KubernetesClusterRequest("local", ClusterSource.MANUAL,
                null, null, null, null, null);

        assertThatThrownBy(() -> service.create(TENANT_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiServerUrl is required");
    }

    @Test
    void update_manual_mergesFieldsAndRestartsWorker() {
        Cluster existing = cluster(TENANT_ID);
        when(repository.findById(TENANT_ID, existing.id())).thenReturn(Optional.of(existing));
        when(encryptor.encrypt("new-token")).thenReturn("enc-new-token");
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        var req = new KubernetesClusterRequest("prod-v2", ClusterSource.MANUAL,
                null, "https://5.6.7.8:6443", null, "new-token", null);

        Cluster result = service.update(TENANT_ID, existing.id(), req);

        assertThat(result.name()).isEqualTo("prod-v2");
        assertThat(result.apiServerUrl()).isEqualTo("https://5.6.7.8:6443");
        assertThat(result.saToken()).isEqualTo("enc-new-token");
        assertThat(result.status()).isEqualTo(ClusterStatus.CONNECTING);
        verify(workerManager).stop(existing.id());
        verify(workerManager).start(result);
    }

    @Test
    void update_manual_keepsExistingCredentialsWhenNullSent() {
        Cluster existing = new Cluster(UUID.randomUUID(), TENANT_ID, "prod", ClusterSource.MANUAL,
                "https://1.2.3.4:6443", "enc-ca", "enc-token", false,
                ClusterStatus.ACTIVE, null, Instant.now(), Instant.now(), null, null);
        when(repository.findById(TENANT_ID, existing.id())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        var req = new KubernetesClusterRequest("prod", ClusterSource.MANUAL,
                null, null, null, null, null);

        Cluster result = service.update(TENANT_ID, existing.id(), req);

        assertThat(result.caCertPem()).isEqualTo("enc-ca");
        assertThat(result.saToken()).isEqualTo("enc-token");
        verify(encryptor, never()).encrypt(any());
    }

    @Test
    void update_kubeconfig_reParsesWhenNewYamlProvided() {
        Cluster existing = new Cluster(UUID.randomUUID(), TENANT_ID, "local", ClusterSource.KUBECONFIG,
                "https://old:6443", null, null, false,
                ClusterStatus.ERROR, "timeout", Instant.now(), Instant.now(), null, null);
        when(repository.findById(TENANT_ID, existing.id())).thenReturn(Optional.of(existing));
        var parsed = new KubeconfigParser.ParsedClusterConfig("https://new:6443", null, "tok", false);
        when(kubeconfigParser.parse("new-yaml")).thenReturn(parsed);
        when(encryptor.encrypt("tok")).thenReturn("enc-tok");
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        var req = new KubernetesClusterRequest("local", ClusterSource.KUBECONFIG,
                "new-yaml", null, null, null, null);

        Cluster result = service.update(TENANT_ID, existing.id(), req);

        assertThat(result.apiServerUrl()).isEqualTo("https://new:6443");
        assertThat(result.status()).isEqualTo(ClusterStatus.CONNECTING);
        verify(kubeconfigParser).parse("new-yaml");
    }

    @Test
    void update_kubeconfig_keepsPreviousConfigWhenNullKubeconfigSent() {
        Cluster existing = new Cluster(UUID.randomUUID(), TENANT_ID, "local", ClusterSource.KUBECONFIG,
                "https://old:6443", "enc-ca", "enc-tok", false,
                ClusterStatus.ACTIVE, null, Instant.now(), Instant.now(), null, null);
        when(repository.findById(TENANT_ID, existing.id())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        var req = new KubernetesClusterRequest("local-renamed", ClusterSource.KUBECONFIG,
                null, null, null, null, null);

        Cluster result = service.update(TENANT_ID, existing.id(), req);

        assertThat(result.name()).isEqualTo("local-renamed");
        assertThat(result.apiServerUrl()).isEqualTo("https://old:6443");
        verify(kubeconfigParser, never()).parse(any());
    }

    @Test
    void update_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findById(TENANT_ID, id)).thenReturn(Optional.empty());

        var req = new KubernetesClusterRequest("prod", ClusterSource.MANUAL,
                null, "https://1.2.3.4:6443", null, null, null);

        assertThatThrownBy(() -> service.update(TENANT_ID, id, req))
                .isInstanceOf(ClusterNotFoundException.class);

        verify(repository, never()).save(any());
        verify(workerManager, never()).stop(any());
    }

    @Test
    void update_kubeconfigMissingServerUrl_throws() {
        Cluster existing = new Cluster(UUID.randomUUID(), TENANT_ID, "local", ClusterSource.KUBECONFIG,
                "https://old:6443", null, null, false,
                ClusterStatus.ERROR, null, Instant.now(), Instant.now(), null, null);
        when(repository.findById(TENANT_ID, existing.id())).thenReturn(Optional.of(existing));
        when(kubeconfigParser.parse("bad-yaml"))
                .thenThrow(new IllegalArgumentException("kubeconfig is missing a 'clusters' section"));

        var req = new KubernetesClusterRequest("local", ClusterSource.KUBECONFIG,
                "bad-yaml", null, null, null, null);

        assertThatThrownBy(() -> service.update(TENANT_ID, existing.id(), req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clusters");
    }

    @Test
    void get_returnsCluster() {
        Cluster cluster = cluster(TENANT_ID);
        when(repository.findById(TENANT_ID, cluster.id())).thenReturn(Optional.of(cluster));

        Cluster result = service.get(TENANT_ID, cluster.id());

        assertThat(result).isEqualTo(cluster);
    }

    @Test
    void get_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findById(TENANT_ID, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(TENANT_ID, id))
                .isInstanceOf(ClusterNotFoundException.class);
    }

    @Test
    void delete_softDeletesAndStopsWorker() {
        Cluster cluster = cluster(TENANT_ID);
        when(repository.findById(TENANT_ID, cluster.id())).thenReturn(Optional.of(cluster));

        service.delete(TENANT_ID, cluster.id());

        verify(repository).softDelete(TENANT_ID, cluster.id());
        verify(workerManager).stop(cluster.id());
    }

    @Test
    void delete_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findById(TENANT_ID, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(TENANT_ID, id))
                .isInstanceOf(ClusterNotFoundException.class);

        verify(repository, never()).softDelete(any(), any());
        verify(workerManager, never()).stop(any());
    }

    private static Cluster cluster(UUID tenantId) {
        Instant now = Instant.now();
        return new Cluster(UUID.randomUUID(), tenantId, "prod", ClusterSource.MANUAL,
                "https://1.2.3.4:6443", null, null, false,
                ClusterStatus.ACTIVE, null, now, now, null, null);
    }
}
