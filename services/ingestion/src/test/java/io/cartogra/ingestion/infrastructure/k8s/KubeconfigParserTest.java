package io.cartogra.ingestion.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KubeconfigParserTest {

    private final KubeconfigParser parser = new KubeconfigParser();

    private static final String KUBECONFIG_WITH_TOKEN = """
            apiVersion: v1
            clusters:
            - cluster:
                server: https://127.0.0.1:6443
                certificate-authority-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0t
              name: local
            contexts:
            - context:
                cluster: local
                user: admin
              name: local
            current-context: local
            users:
            - name: admin
              user:
                token: my-sa-token
            """;

    private static final String KUBECONFIG_SKIP_TLS = """
            apiVersion: v1
            clusters:
            - cluster:
                server: https://127.0.0.1:6443
                insecure-skip-tls-verify: true
              name: local
            contexts:
            - context:
                cluster: local
                user: dev
              name: local
            current-context: local
            users:
            - name: dev
              user: {}
            """;

    @Test
    void parse_extractsApiServerUrl() {
        var result = parser.parse(KUBECONFIG_WITH_TOKEN);

        assertThat(result.apiServerUrl()).isEqualTo("https://127.0.0.1:6443/");
    }

    @Test
    void parse_extractsToken() {
        var result = parser.parse(KUBECONFIG_WITH_TOKEN);

        assertThat(result.saToken()).isEqualTo("my-sa-token");
    }

    @Test
    void parse_extractsCaCert() {
        var result = parser.parse(KUBECONFIG_WITH_TOKEN);

        assertThat(result.caCertPem()).isEqualTo("LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0t");
    }

    @Test
    void parse_skipTlsVerify_whenInsecureFlag() {
        var result = parser.parse(KUBECONFIG_SKIP_TLS);

        assertThat(result.skipTlsVerify()).isTrue();
    }

    @Test
    void parse_invalidYaml_throwsIllegalArgument() {
        assertThatThrownBy(() -> parser.parse("not: valid: kubeconfig: ["))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid YAML");
    }

    @Test
    void parse_missingClusters_throwsIllegalArgument() {
        String onlyUsers = """
                kind: Config
                users:
                - name: kind-cartogra
                  user:
                    client-certificate-data: dGVzdA==
                    client-key-data: dGVzdA==
                """;
        assertThatThrownBy(() -> parser.parse(onlyUsers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'clusters' section");
    }

    @Test
    void parse_missingCurrentContext_throwsIllegalArgument() {
        String noCurrentContext = """
                apiVersion: v1
                clusters:
                - cluster:
                    server: https://127.0.0.1:6443
                  name: local
                users:
                - name: admin
                  user:
                    token: tok
                """;
        assertThatThrownBy(() -> parser.parse(noCurrentContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current-context");
    }

    @Test
    void parse_clusterMissingServer_throwsIllegalArgument() {
        String noServer = """
                apiVersion: v1
                clusters:
                - cluster: {}
                  name: local
                current-context: local
                users:
                - name: admin
                  user:
                    token: tok
                """;
        assertThatThrownBy(() -> parser.parse(noServer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server");
    }
}
