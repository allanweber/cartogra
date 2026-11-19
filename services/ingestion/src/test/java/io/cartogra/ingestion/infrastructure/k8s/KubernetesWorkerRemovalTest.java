package io.cartogra.ingestion.infrastructure.k8s;

import io.cartogra.ingestion.domain.PresentService;
import io.cartogra.ingestion.domain.ServiceRemovedPayload;
import io.cartogra.ingestion.domain.ServiceResyncedPayload;
import io.cartogra.ingestion.infrastructure.kafka.ServiceChangedProducer;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true, https = false)
class KubernetesWorkerRemovalTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String CLUSTER = "prod";

    KubernetesMockServer mockServer;
    KubernetesClient client;

    private ServiceChangedProducer producer;
    private KubernetesWorker worker;

    @BeforeEach
    void setUp() {
        producer = mock(ServiceChangedProducer.class);
        worker = new KubernetesWorker(client, producer, CLUSTER, TENANT_ID);
    }

    @Test
    void resync_sendsTheFullSetOfTenantServices() {
        Instant before = Instant.now();
        createNamespace("shop", TENANT_ID.toString());
        createNamespace("other", UUID.randomUUID().toString());
        createService("shop", "cart");
        createService("shop", "checkout");
        createService("other", "foreign");

        worker.resync();

        var captor = ArgumentCaptor.forClass(ServiceResyncedPayload.class);
        verify(producer).publishResynced(captor.capture());
        ServiceResyncedPayload payload = captor.getValue();
        assertThat(payload.tenantId()).isEqualTo(TENANT_ID);
        assertThat(payload.k8sCluster()).isEqualTo(CLUSTER);
        assertThat(payload.observedAt()).isAfterOrEqualTo(before);
        assertThat(payload.services()).containsExactlyInAnyOrder(
                new PresentService("shop", "cart"), new PresentService("shop", "checkout"));
    }

    @Test
    void resync_withNoTenantServices_sendsAnEmptySet() {
        createNamespace("shop", TENANT_ID.toString());

        worker.resync();

        var captor = ArgumentCaptor.forClass(ServiceResyncedPayload.class);
        verify(producer).publishResynced(captor.capture());
        assertThat(captor.getValue().services()).isEmpty();
    }

    @Test
    void resync_whenAListCallFails_sendsNoSet() {
        KubernetesClient failing = mock(KubernetesClient.class);
        when(failing.namespaces()).thenThrow(new KubernetesClientException("api server down"));
        var failingWorker = new KubernetesWorker(failing, producer, CLUSTER, TENANT_ID);

        assertThatThrownBy(failingWorker::resync).isInstanceOf(KubernetesClientException.class);

        verify(producer, never()).publishResynced(any());
    }

    @Test
    void deletedService_sendsServiceRemoved() {
        createNamespace("shop", TENANT_ID.toString());
        createService("shop", "cart");
        worker.start();
        try {
            client.services().inNamespace("shop").withName("cart").delete();

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(producer).publishRemoved(new ServiceRemovedPayload(TENANT_ID, CLUSTER, "shop", "cart")));
        } finally {
            worker.stop();
        }
    }

    @Test
    void deletedService_inAForeignNamespace_sendsNothing() throws Exception {
        createNamespace("other", UUID.randomUUID().toString());
        createService("other", "foreign");
        worker.start();
        try {
            client.services().inNamespace("other").withName("foreign").delete();

            TimeUnit.MILLISECONDS.sleep(500);
            verify(producer, never()).publishRemoved(any());
        } finally {
            worker.stop();
        }
    }

    private void createNamespace(String name, String tenantLabel) {
        client.namespaces().resource(new NamespaceBuilder()
                .withNewMetadata().withName(name).withLabels(Map.of("cartogra.io/tenant-id", tenantLabel)).endMetadata()
                .build()).create();
    }

    private void createService(String namespace, String name) {
        client.services().inNamespace(namespace).resource(new ServiceBuilder()
                .withNewMetadata().withName(name).withNamespace(namespace).endMetadata()
                .withNewSpec().endSpec()
                .build()).create();
    }
}
