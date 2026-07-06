package io.cartogra.ingestion;

import io.cartogra.ingestion.infrastructure.k8s.KubernetesWorker;
import io.cartogra.ingestion.infrastructure.kafka.ServiceDiscoveredProducer;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointsBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@SpringBootTest
class KubernetesWorkerIT {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final String NS = "cartogra-ns";
    private static final String SVC_NAME = "payments-svc";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresTestSupport.POSTGRES.start();
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.flyway.schemas", () -> "ingestion");
        registry.add("spring.flyway.default-schema", () -> "ingestion");
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Autowired
    ServiceDiscoveredProducer serviceDiscoveredProducer;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @SuppressWarnings("unchecked")
    void handleServiceEvent_publishesDiscoveredEventWithHealthyStatus() throws Exception {
        Namespace ns = new NamespaceBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName(NS)
                        .addToLabels("cartogra.io/tenant-id", TENANT_ID.toString())
                        .build())
                .build();

        EndpointSubset subset = new EndpointSubset();
        EndpointAddress addr = new EndpointAddress();
        addr.setIp("10.0.0.1");
        subset.setAddresses(List.of(addr));
        Endpoints endpoints = new EndpointsBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(SVC_NAME).withNamespace(NS).build())
                .withSubsets(subset)
                .build();

        KubernetesClient kubernetesClient = mock(KubernetesClient.class);

        NonNamespaceOperation<Namespace, ?, Resource<Namespace>> nsOps = mock(NonNamespaceOperation.class);
        Resource<Namespace> nsResource = mock(Resource.class);
        doReturn(nsOps).when(kubernetesClient).namespaces();
        doReturn(nsResource).when(nsOps).withName(NS);
        doReturn(ns).when(nsResource).get();

        MixedOperation<Endpoints, ?, Resource<Endpoints>> epOps = mock(MixedOperation.class);
        NonNamespaceOperation<Endpoints, ?, Resource<Endpoints>> epNsOps = mock(NonNamespaceOperation.class);
        Resource<Endpoints> epResource = mock(Resource.class);
        doReturn(epOps).when(kubernetesClient).endpoints();
        doReturn(epNsOps).when(epOps).inNamespace(NS);
        doReturn(epResource).when(epNsOps).withName(SVC_NAME);
        doReturn(endpoints).when(epResource).get();

        Service svc = new ServiceBuilder()
                .withMetadata(new ObjectMetaBuilder().withName(SVC_NAME).withNamespace(NS).build())
                .build();
        MixedOperation<Service, ?, ServiceResource<Service>> svcOps = mock(MixedOperation.class);
        NonNamespaceOperation<Service, ?, ServiceResource<Service>> svcNsOps = mock(NonNamespaceOperation.class);
        ServiceResource<Service> svcResource = mock(ServiceResource.class);
        doReturn(svcOps).when(kubernetesClient).services();
        doReturn(svcNsOps).when(svcOps).inNamespace(NS);
        doReturn(svcResource).when(svcNsOps).withName(SVC_NAME);
        doReturn(svc).when(svcResource).get();

        KubernetesWorker worker = new KubernetesWorker(kubernetesClient, serviceDiscoveredProducer, "test-cluster");
        worker.handleServiceEvent(NS, SVC_NAME);

        // K8s key is cluster/namespace/name (externalId is null for K8s sources).
        String expectedKey = "test-cluster/" + NS + "/" + SVC_NAME;
        List<ConsumerRecord<String, String>> messages = pollTopic(
                "cartogra.ingestion.service.discovered", bootstrapServers, expectedKey);

        assertThat(messages).isNotEmpty();
        JsonNode envelope = objectMapper.readTree(messages.get(0).value());
        JsonNode payload = envelope.get("payload");
        assertThat(objectMapper.treeToValue(payload.get("source"), String.class)).isEqualTo("kubernetes");
        assertThat(objectMapper.treeToValue(payload.get("healthStatus"), String.class)).isEqualTo("HEALTHY");
        assertThat(objectMapper.treeToValue(payload.get("tenantId"), String.class)).isEqualTo(TENANT_ID.toString());
        // K8s worker no longer sets externalId — identity is the k8sCluster/k8sNamespace/name triple.
        assertThat(payload.get("externalId").isNull()).isTrue();
    }

    private List<ConsumerRecord<String, String>> pollTopic(String topic, String servers, String key) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers,
                ConsumerConfig.GROUP_ID_CONFIG, "k8s-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) records.add(r);
                });
                if (!records.isEmpty()) break;
            }
        }
        return records;
    }
}
