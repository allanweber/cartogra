package io.cartogra.ingestion.domain;

import io.cartogra.common.identity.Actor;
import io.cartogra.common.audit.AuditEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionAuditRecorderTest {

    private final List<AuditEvent> recorded = new ArrayList<>();
    private final IngestionAuditRecorder recorder = new IngestionAuditRecorder(recorded::add, new ObjectMapper());

    private static Cluster cluster(String saToken, String kubeconfig) {
        Instant now = Instant.now();
        return new Cluster(UUID.randomUUID(), UUID.randomUUID(), "prod", ClusterSource.KUBECONFIG,
                "https://k8s.example:6443", "-----BEGIN CERTIFICATE-----", saToken, false,
                ClusterStatus.ACTIVE, null, now, now, null, kubeconfig);
    }

    private static ScmConnection connection(String config) {
        Instant now = Instant.now();
        return new ScmConnection(UUID.randomUUID(), UUID.randomUUID(), "github", config, false, 15,
                null, null, null, null, false, now, now, null);
    }

    @Test
    void clusterPayloadsNeverCarryCredentials() {
        Cluster before = cluster("sa-token-old", "kubeconfig-old");
        Cluster after = new Cluster(before.id(), before.tenantId(), before.name(), before.source(),
                before.apiServerUrl(), before.caCertPem(), "sa-token-new", false, before.status(), null,
                before.createdAt(), Instant.now(), null, "kubeconfig-new");

        recorder.clusterCreated(Actor.ANONYMOUS, before);
        recorder.clusterUpdated(Actor.ANONYMOUS, before, after);
        recorder.clusterDeleted(Actor.ANONYMOUS, after);

        assertThat(recorded).hasSize(3);
        assertThat(recorded.toString()).doesNotContain("sa-token", "kubeconfig-", "BEGIN CERTIFICATE");
        assertThat(recorded.get(1).payload()).containsEntry("credentialsChanged", true);
    }

    @Test
    void scmConnectionPayloadsNeverCarryConfig() {
        ScmConnection before = connection("{\"token\":\"enc:old\"}");
        ScmConnection after = new ScmConnection(before.id(), before.tenantId(), "github", "{\"token\":\"enc:new\"}",
                false, 15, null, null, null, null, false, before.createdAt(), Instant.now(), null);

        recorder.scmConnectionCreated(Actor.ANONYMOUS, before);
        recorder.scmConnectionUpdated(Actor.ANONYMOUS, before, after);

        assertThat(recorded).hasSize(2);
        assertThat(recorded.toString()).doesNotContain("enc:", "token");
        assertThat(recorded.get(1).payload()).containsEntry("configChanged", true);
    }
}
