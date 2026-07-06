package io.cartogra.ingestion.infrastructure.k8s;

import io.fabric8.kubernetes.client.Config;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

@Component
public class KubeconfigParser {

    public ParsedClusterConfig parse(String kubeconfigYaml) {
        validateStructure(kubeconfigYaml);
        try {
            Config config = Config.fromKubeconfig(kubeconfigYaml);
            String token = config.getAutoOAuthToken() != null ? config.getAutoOAuthToken() : config.getOauthToken();
            return new ParsedClusterConfig(
                    config.getMasterUrl(),
                    config.getCaCertData(),
                    token,
                    config.isTrustCerts()
            );
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid kubeconfig: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private void validateStructure(String kubeconfigYaml) {
        Map<String, Object> root;
        try {
            root = new Yaml().load(kubeconfigYaml);
        } catch (Exception ex) {
            throw new IllegalArgumentException("kubeconfig is not valid YAML: " + ex.getMessage(), ex);
        }
        if (root == null) {
            throw new IllegalArgumentException("kubeconfig is empty");
        }

        List<?> clusters = (List<?>) root.get("clusters");
        if (clusters == null || clusters.isEmpty()) {
            throw new IllegalArgumentException("kubeconfig is missing a 'clusters' section — run: kind get kubeconfig --name <cluster-name>");
        }

        Map<String, Object> firstCluster = (Map<String, Object>) clusters.get(0);
        Map<String, Object> clusterBlock = firstCluster != null ? (Map<String, Object>) firstCluster.get("cluster") : null;
        String server = clusterBlock != null ? (String) clusterBlock.get("server") : null;
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("kubeconfig clusters[0] is missing a 'server' URL");
        }

        if (root.get("current-context") == null) {
            throw new IllegalArgumentException("kubeconfig is missing 'current-context' — ensure the context is set");
        }
    }

    public record ParsedClusterConfig(
            String apiServerUrl,
            @Nullable String caCertPem,
            @Nullable String saToken,
            boolean skipTlsVerify
    ) {}
}
