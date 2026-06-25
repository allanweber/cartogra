package io.cartogra.ingestion;

import io.cartogra.ingestion.domain.ServiceDiscoveredPayload;
import io.cartogra.ingestion.infrastructure.k8s.KubernetesWorker;
import io.cartogra.ingestion.infrastructure.kafka.ServiceDiscoveredProducer;
import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointsBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class KubernetesWorkerHealthMappingTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final String NS = "test-ns";
    private static final String SVC_NAME = "my-svc";

    @Mock KubernetesClient kubernetesClient;
    @Mock ServiceDiscoveredProducer producer;

    private KubernetesWorker worker;

    @BeforeEach
    void setUp() {
        worker = new KubernetesWorker(kubernetesClient, producer);

        Namespace ns = new NamespaceBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName(NS)
                        .withLabels(Map.of("cartogra.io/tenant-id", TENANT_ID.toString()))
                        .build())
                .build();

        @SuppressWarnings("unchecked")
        NonNamespaceOperation<Namespace, ?, Resource<Namespace>> nsOps = mock(NonNamespaceOperation.class);
        @SuppressWarnings("unchecked")
        Resource<Namespace> nsResource = mock(Resource.class);
        doReturn(nsOps).when(kubernetesClient).namespaces();
        doReturn(nsResource).when(nsOps).withName(NS);
        doReturn(ns).when(nsResource).get();
    }

    @Test
    void healthyWhenAllAddressesReady() {
        stubEndpoints(endpoints(List.of("1.1.1.1"), List.of()));
        assertThat(captureHealthStatus()).isEqualTo("HEALTHY");
    }

    @Test
    void unhealthyWhenAllAddressesNotReady() {
        stubEndpoints(endpoints(List.of(), List.of("1.1.1.1")));
        assertThat(captureHealthStatus()).isEqualTo("UNHEALTHY");
    }

    @Test
    void degradedWhenMixedReadyAndNotReady() {
        stubEndpoints(endpoints(List.of("1.1.1.1"), List.of("2.2.2.2")));
        assertThat(captureHealthStatus()).isEqualTo("DEGRADED");
    }

    @Test
    void unknownWhenNoSubsets() {
        stubEndpoints(new EndpointsBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(SVC_NAME).withNamespace(NS).build())
                .build());
        assertThat(captureHealthStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void unknownWhenEndpointsAbsent() {
        stubEndpoints(null);
        assertThat(captureHealthStatus()).isEqualTo("UNKNOWN");
    }

    private String captureHealthStatus() {
        ArgumentCaptor<ServiceDiscoveredPayload> captor = ArgumentCaptor.forClass(ServiceDiscoveredPayload.class);
        worker.handleServiceEvent(NS, SVC_NAME);
        org.mockito.Mockito.verify(producer).publish(captor.capture());
        return captor.getValue().healthStatus();
    }

    @SuppressWarnings("unchecked")
    private void stubEndpoints(Endpoints endpoints) {
        var epOps = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var epNsOps = mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var epResource = mock(Resource.class);
        doReturn(epOps).when(kubernetesClient).endpoints();
        doReturn(epNsOps).when(epOps).inNamespace(NS);
        doReturn(epResource).when(epNsOps).withName(SVC_NAME);
        doReturn(endpoints).when(epResource).get();
    }

    private Endpoints endpoints(List<String> readyIps, List<String> notReadyIps) {
        EndpointSubset subset = new EndpointSubset();
        if (!readyIps.isEmpty()) {
            subset.setAddresses(readyIps.stream().map(ip -> {
                EndpointAddress a = new EndpointAddress(); a.setIp(ip); return a;
            }).toList());
        }
        if (!notReadyIps.isEmpty()) {
            subset.setNotReadyAddresses(notReadyIps.stream().map(ip -> {
                EndpointAddress a = new EndpointAddress(); a.setIp(ip); return a;
            }).toList());
        }
        return new EndpointsBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(SVC_NAME).withNamespace(NS).build())
                .withSubsets(subset)
                .build();
    }
}
