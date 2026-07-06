package io.cartogra.ingestion.domain;

import io.cartogra.ingestion.api.dto.KubernetesClusterRequest;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandConsumer;
import io.cartogra.ingestion.infrastructure.kafka.SyncResultProducer;
import io.cartogra.ingestion.infrastructure.k8s.ClusterWorkerManager;
import io.cartogra.ingestion.infrastructure.k8s.CredentialEncryptor;
import io.cartogra.ingestion.infrastructure.k8s.KubeconfigParser;
import io.cartogra.ingestion.repository.ClusterRepository;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.kafka.bootstrap-servers=localhost:9092"
})
class ClusterServiceSecurityTest {

    @MockitoBean ClusterRepository clusterRepository;
    @MockitoBean ClusterWorkerManager clusterWorkerManager;
    @MockitoBean CredentialEncryptor credentialEncryptor;
    @MockitoBean KubeconfigParser kubeconfigParser;
    @MockitoBean SyncResultProducer syncResultProducer;
    @MockitoBean SyncCommandConsumer syncCommandConsumer;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresTestSupport.POSTGRES.start();
        registry.add("spring.datasource.url", PostgresTestSupport.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }

    @Autowired ClusterService clusterService;

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    void create_withoutAuthentication_throwsSecurityException() {
        assertThatThrownBy(() -> clusterService.create(TENANT, manualRequest()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void create_asViewer_throwsAccessDenied() {
        assertThatThrownBy(() -> clusterService.create(TENANT, manualRequest()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_asAdmin_succeeds() {
        when(credentialEncryptor.encrypt(any())).thenAnswer(i -> i.getArgument(0));
        when(clusterRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        clusterService.create(TENANT, manualRequest());
        // no exception — ADMIN is allowed
    }

    @Test
    void list_withoutAuthentication_throwsSecurityException() {
        assertThatThrownBy(() -> clusterService.list(TENANT, 20, 0))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void list_asMember_throwsAccessDenied() {
        assertThatThrownBy(() -> clusterService.list(TENANT, 20, 0))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_asAdmin_succeeds() {
        when(clusterRepository.count(TENANT)).thenReturn(0L);
        when(clusterRepository.findAll(any(), any(Integer.class), any(Integer.class))).thenReturn(java.util.List.of());

        clusterService.list(TENANT, 20, 0);
    }

    @Test
    void delete_withoutAuthentication_throwsSecurityException() {
        assertThatThrownBy(() -> clusterService.delete(TENANT, UUID.randomUUID()))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_asAdmin_succeeds() {
        UUID id = UUID.randomUUID();
        when(clusterRepository.findById(TENANT, id)).thenReturn(java.util.Optional.of(
                new Cluster(id, TENANT, "c", ClusterSource.MANUAL, "https://x", null, null,
                        false, ClusterStatus.CONNECTING, null,
                        java.time.Instant.now(), java.time.Instant.now(), null, null)));

        clusterService.delete(TENANT, id);
    }

    private static KubernetesClusterRequest manualRequest() {
        return new KubernetesClusterRequest("my-cluster", ClusterSource.MANUAL,
                null, "https://1.2.3.4:6443", null, null, false);
    }
}
