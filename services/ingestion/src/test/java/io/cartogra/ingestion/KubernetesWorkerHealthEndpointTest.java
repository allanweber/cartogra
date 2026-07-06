package io.cartogra.ingestion;

import io.cartogra.ingestion.domain.ServiceDiscoveredPayload;
import io.cartogra.ingestion.infrastructure.k8s.KubernetesWorker;
import io.cartogra.ingestion.infrastructure.kafka.ServiceDiscoveredProducer;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.EndpointsBuilder;
import io.fabric8.kubernetes.api.model.HTTPGetActionBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@EnableKubernetesMockClient(crud = true)
class KubernetesWorkerHealthEndpointTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final String NS = "test-ns";
    private static final String SVC_NAME = "my-svc";

    // Injected by @EnableKubernetesMockClient
    KubernetesClient client;

    ServiceDiscoveredProducer producer;
    KubernetesWorker worker;

    @BeforeEach
    void setUp() {
        producer = mock(ServiceDiscoveredProducer.class);
        worker = new KubernetesWorker(client, producer, "test-cluster");

        client.namespaces().resource(new NamespaceBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName(NS)
                        .withLabels(Map.of("cartogra.io/tenant-id", TENANT_ID.toString()))
                        .build())
                .build()).create();

        // Endpoints with one ready address so computeHealthStatus returns HEALTHY
        EndpointSubset subset = new EndpointSubset();
        EndpointAddress addr = new EndpointAddress();
        addr.setIp("10.0.0.1");
        subset.setAddresses(List.of(addr));
        client.endpoints().inNamespace(NS).resource(new EndpointsBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(SVC_NAME).withNamespace(NS).build())
                .withSubsets(subset)
                .build()).create();
    }

    @Test
    void extractsHealthEndpointUsingReadinessProbeAndServicePort() {
        client.services().inNamespace(NS).resource(new ServiceBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(SVC_NAME).withNamespace(NS).build())
                .withNewSpec()
                    .withSelector(Map.of("app", SVC_NAME))
                    .withPorts(new ServicePortBuilder()
                            .withPort(8090).withTargetPort(new IntOrString(8080)).build())
                .endSpec()
                .build()).create();

        client.pods().inNamespace(NS).resource(new PodBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("pod-1").withNamespace(NS)
                        .withLabels(Map.of("app", SVC_NAME))
                        .build())
                .withNewSpec()
                    .withContainers(new ContainerBuilder()
                            .withName("app")
                            .withPorts(new ContainerPortBuilder().withContainerPort(8080).build())
                            .withReadinessProbe(new ProbeBuilder()
                                    .withHttpGet(new HTTPGetActionBuilder()
                                            .withPath("/actuator/health/ready")
                                            .withPort(new IntOrString(8080))
                                            .build())
                                    .build())
                            .build())
                .endSpec()
                .build()).create();

        ServiceDiscoveredPayload payload = capturePayload();
        assertThat(payload.healthEndpoint())
                .isEqualTo("http://my-svc.test-ns.svc.cluster.local:8090/actuator/health/ready");
    }

    @Test
    void fallsBackToLivenessProbeWhenNoReadinessProbe() {
        client.services().inNamespace(NS).resource(new ServiceBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(SVC_NAME).withNamespace(NS).build())
                .withNewSpec()
                    .withSelector(Map.of("app", SVC_NAME))
                    .withPorts(new ServicePortBuilder()
                            .withPort(9000).withTargetPort(new IntOrString(9000)).build())
                .endSpec()
                .build()).create();

        client.pods().inNamespace(NS).resource(new PodBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("pod-1").withNamespace(NS)
                        .withLabels(Map.of("app", SVC_NAME))
                        .build())
                .withNewSpec()
                    .withContainers(new ContainerBuilder()
                            .withName("app")
                            .withLivenessProbe(new ProbeBuilder()
                                    .withHttpGet(new HTTPGetActionBuilder()
                                            .withPath("/health")
                                            .withPort(new IntOrString(9000))
                                            .build())
                                    .build())
                            .build())
                .endSpec()
                .build()).create();

        assertThat(capturePayload().healthEndpoint())
                .isEqualTo("http://my-svc.test-ns.svc.cluster.local:9000/health");
    }

    @Test
    void healthEndpointIsNullWhenContainerHasNoProbe() {
        client.services().inNamespace(NS).resource(new ServiceBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(SVC_NAME).withNamespace(NS).build())
                .withNewSpec()
                    .withSelector(Map.of("app", SVC_NAME))
                    .withPorts(new ServicePortBuilder()
                            .withPort(8080).withTargetPort(new IntOrString(8080)).build())
                .endSpec()
                .build()).create();

        client.pods().inNamespace(NS).resource(new PodBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("pod-1").withNamespace(NS)
                        .withLabels(Map.of("app", SVC_NAME))
                        .build())
                .withNewSpec()
                    .withContainers(new ContainerBuilder().withName("app").build())
                .endSpec()
                .build()).create();

        assertThat(capturePayload().healthEndpoint()).isNull();
    }

    @Test
    void healthEndpointIsNullWhenNoMatchingPodsExist() {
        client.services().inNamespace(NS).resource(new ServiceBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(SVC_NAME).withNamespace(NS).build())
                .withNewSpec()
                    .withSelector(Map.of("app", SVC_NAME))
                    .withPorts(new ServicePortBuilder()
                            .withPort(8080).withTargetPort(new IntOrString(8080)).build())
                .endSpec()
                .build()).create();
        // No pods created — selector matches nothing

        assertThat(capturePayload().healthEndpoint()).isNull();
    }

    private ServiceDiscoveredPayload capturePayload() {
        ArgumentCaptor<ServiceDiscoveredPayload> captor = ArgumentCaptor.forClass(ServiceDiscoveredPayload.class);
        worker.handleServiceEvent(NS, SVC_NAME);
        verify(producer).publish(captor.capture());
        return captor.getValue();
    }
}
