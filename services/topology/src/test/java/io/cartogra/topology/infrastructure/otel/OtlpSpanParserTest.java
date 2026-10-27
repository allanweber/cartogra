package io.cartogra.topology.infrastructure.otel;

import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.SpanKind;
import io.cartogra.topology.domain.SpanObservation;
import io.cartogra.topology.infrastructure.otel.OtlpSpanParser.TenantSpans;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpSpanParserTest {

    private final OtlpSpanParser mapper = new OtlpSpanParser();
    private final JsonMapper json = JsonMapper.builder().build();

    private List<TenantSpans> map(String spanJson, String resourceAttrs) {
        String body = """
                {"resourceSpans":[{"resource":{"attributes":[%s]},
                 "scopeSpans":[{"scope":{"name":"x"},"spans":[%s]}]}]}
                """.formatted(resourceAttrs, spanJson);
        return mapper.map(json.readValue(body, OtlpTraceRequest.class));
    }

    private static final String SERVICE = """
            {"key":"service.name","value":{"stringValue":"checkout"}},
            {"key":"cartogra.tenant.id","value":{"stringValue":"t-1"}}
            """;

    private static String span(String kind, String attrs) {
        return """
                {"traceId":"aa","spanId":"bb","parentSpanId":"","kind":%s,"unknownField":1,"attributes":[%s]}
                """.formatted(kind, attrs);
    }

    @Test
    void clientSpanWithPeerServiceAndHttpMethod() {
        var groups = map(span("3", """
                {"key":"peer.service","value":{"stringValue":"payments"}},
                {"key":"http.request.method","value":{"stringValue":"GET"}}"""), SERVICE);

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().tenantRaw()).isEqualTo("t-1");
        SpanObservation obs = groups.getFirst().observations().getFirst();
        assertThat(obs.serviceName()).isEqualTo("checkout");
        assertThat(obs.kind()).isEqualTo(SpanKind.CLIENT);
        assertThat(obs.peerName()).isEqualTo("payments");
        assertThat(obs.protocol()).isEqualTo(DependencyProtocol.HTTP);
        assertThat(obs.parentSpanId()).isNull();
    }

    @Test
    void stringEnumKindAndLegacyHttpMethodAttribute() {
        var obs = map(span("\"SPAN_KIND_SERVER\"", """
                {"key":"http.method","value":{"stringValue":"POST"}}"""), SERVICE)
                .getFirst().observations().getFirst();

        assertThat(obs.kind()).isEqualTo(SpanKind.SERVER);
        assertThat(obs.protocol()).isEqualTo(DependencyProtocol.HTTP);
    }

    @Test
    void grpcSystemWinsOverHttpAttributes() {
        var obs = map(span("3", """
                {"key":"rpc.system","value":{"stringValue":"grpc"}},
                {"key":"http.request.method","value":{"stringValue":"POST"}}"""), SERVICE)
                .getFirst().observations().getFirst();

        assertThat(obs.protocol()).isEqualTo(DependencyProtocol.GRPC);
    }

    @Test
    void intValueAttributesAreStringified() {
        var obs = map(span("3", """
                {"key":"peer.service","value":{"intValue":"42"}},
                {"key":"http.request.method","value":{"stringValue":"GET"}}"""), SERVICE)
                .getFirst().observations().getFirst();

        assertThat(obs.peerName()).isEqualTo("42");
    }

    @Test
    void hostPortIsStripped() {
        assertThat(hostOf("10.1.2.3:8080")).isEqualTo("10.1.2.3");
        assertThat(hostOf("payments.internal")).isEqualTo("payments.internal");
        assertThat(hostOf("[::1]:9000")).isEqualTo("::1");
        assertThat(hostOf("fe80::1")).isEqualTo("fe80::1");
    }

    private String hostOf(String address) {
        return map(span("3", """
                {"key":"server.address","value":{"stringValue":"%s"}},
                {"key":"http.request.method","value":{"stringValue":"GET"}}""".formatted(address)), SERVICE)
                .getFirst().observations().getFirst().peerHost();
    }

    @Test
    void noProtocolAttributesGivesNullProtocol() {
        var obs = map(span("3", """
                {"key":"peer.service","value":{"stringValue":"payments"}}"""), SERVICE)
                .getFirst().observations().getFirst();

        assertThat(obs.protocol()).isNull();
    }

    @Test
    void internalKindIsDropped() {
        assertThat(map(span("1", ""), SERVICE).getFirst().observations()).isEmpty();
    }

    @Test
    void dbSystemOnClientGivesDbProtocolOverHttp() {
        var obs = map(span("3", """
                {"key":"db.system","value":{"stringValue":"postgresql"}},
                {"key":"db.namespace","value":{"stringValue":"ledger-db"}},
                {"key":"http.request.method","value":{"stringValue":"GET"}}"""), SERVICE)
                .getFirst().observations().getFirst();

        assertThat(obs.protocol()).isEqualTo(DependencyProtocol.DB);
        assertThat(obs.dbName()).isEqualTo("ledger-db");
    }

    @Test
    void legacyDbNameIsUsedWhenNamespaceIsAbsent() {
        var obs = map(span("3", """
                {"key":"db.system","value":{"stringValue":"postgresql"}},
                {"key":"db.name","value":{"stringValue":"ledger"}}"""), SERVICE)
                .getFirst().observations().getFirst();

        assertThat(obs.dbName()).isEqualTo("ledger");
    }

    @Test
    void kafkaProducerAndConsumerKindsCarryDestination() {
        String attrs = """
                {"key":"messaging.system","value":{"stringValue":"kafka"}},
                {"key":"messaging.destination.name","value":{"stringValue":"payments.completed"}}""";
        var producer = map(span("4", attrs), SERVICE).getFirst().observations().getFirst();
        var consumer = map(span("\"SPAN_KIND_CONSUMER\"", attrs), SERVICE).getFirst().observations().getFirst();

        assertThat(producer.kind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(consumer.kind()).isEqualTo(SpanKind.CONSUMER);
        assertThat(producer.protocol()).isEqualTo(DependencyProtocol.KAFKA);
        assertThat(producer.destination()).isEqualTo("payments.completed");
    }

    @Test
    void nonKafkaTemporaryOrMissingDestinationGivesNullProtocol() {
        var rabbit = map(span("4", """
                {"key":"messaging.system","value":{"stringValue":"rabbitmq"}},
                {"key":"messaging.destination.name","value":{"stringValue":"orders"}}"""), SERVICE);
        var temporary = map(span("5", """
                {"key":"messaging.system","value":{"stringValue":"kafka"}},
                {"key":"messaging.destination.name","value":{"stringValue":"reply-1"}},
                {"key":"messaging.destination.temporary","value":{"boolValue":true}}"""), SERVICE);
        var noDestination = map(span("4", """
                {"key":"messaging.system","value":{"stringValue":"kafka"}}"""), SERVICE);

        assertThat(rabbit.getFirst().observations().getFirst().protocol()).isNull();
        assertThat(temporary.getFirst().observations().getFirst().protocol()).isNull();
        assertThat(noDestination.getFirst().observations().getFirst().protocol()).isNull();
    }

    @Test
    void dbSystemOnProducerIsNotDb() {
        var obs = map(span("4", """
                {"key":"db.system","value":{"stringValue":"postgresql"}}"""), SERVICE)
                .getFirst().observations().getFirst();

        assertThat(obs.protocol()).isNull();
    }

    @Test
    void missingTenantYieldsNullRawAndMissingServiceNameYieldsNoObservations() {
        var noTenant = map(span("3", ""), """
                {"key":"service.name","value":{"stringValue":"checkout"}}""");
        assertThat(noTenant.getFirst().tenantRaw()).isNull();
        assertThat(noTenant.getFirst().observations()).hasSize(1);

        var noService = map(span("3", ""), """
                {"key":"cartogra.tenant.id","value":{"stringValue":"t-1"}}""");
        assertThat(noService.getFirst().observations()).isEmpty();
    }
}
