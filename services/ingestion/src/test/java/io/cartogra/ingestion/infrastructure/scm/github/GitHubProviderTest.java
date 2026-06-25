package io.cartogra.ingestion.infrastructure.scm.github;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.cartogra.ingestion.domain.OwnershipMap;
import io.cartogra.ingestion.domain.ScmConnectionConfig;
import io.cartogra.ingestion.domain.ScmRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class GitHubProviderTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private ScmConnectionConfig config(String org) {
        return new ScmConnectionConfig(
                UUID.randomUUID(), UUID.randomUUID(), "github",
                Map.of("token", "test-token", "org", org,
                        "apiBaseUrl", wireMock.baseUrl()));
    }

    @Test
    void listRepositories_returnsActiveRepos() {
        wireMock.stubFor(get(urlPathEqualTo("/orgs/acme/repos"))
                .willReturn(okJson("""
                        [
                          {"id": 1, "name": "payments", "full_name": "acme/payments",
                           "default_branch": "main", "archived": false},
                          {"id": 2, "name": "legacy", "full_name": "acme/legacy",
                           "default_branch": "master", "archived": true}
                        ]
                        """)));

        var provider = new GitHubProvider();
        List<ScmRepository> repos = provider.listRepositories(config("acme"));

        assertThat(repos).hasSize(2);
        assertThat(repos.get(0).name()).isEqualTo("payments");
        assertThat(repos.get(0).archived()).isFalse();
        assertThat(repos.get(1).name()).isEqualTo("legacy");
        assertThat(repos.get(1).archived()).isTrue();
    }

    @Test
    void getFileContents_returnsDecodedContent() {
        String encoded = java.util.Base64.getEncoder()
                .encodeToString("# CODEOWNERS\n* @acme/platform\n".getBytes());
        wireMock.stubFor(get(urlPathEqualTo("/repos/acme/payments/contents/CODEOWNERS"))
                .willReturn(okJson("{\"content\": \"" + encoded + "\"}")));

        var provider = new GitHubProvider();
        Optional<String> content = provider.getFileContents(config("acme"), "acme/payments", "CODEOWNERS");

        assertThat(content).isPresent();
        assertThat(content.get()).contains("@acme/platform");
    }

    @Test
    void getFileContents_returnsEmptyWhenNotFound() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/acme/payments/contents/CODEOWNERS"))
                .willReturn(notFound()));

        var provider = new GitHubProvider();
        Optional<String> content = provider.getFileContents(config("acme"), "acme/payments", "CODEOWNERS");

        assertThat(content).isEmpty();
    }

    @Test
    void resolveOwnership_parsesCodeOwners() {
        String encoded = java.util.Base64.getEncoder()
                .encodeToString("* @acme/platform\n/src @acme/backend\n".getBytes());
        wireMock.stubFor(get(urlPathEqualTo("/repos/acme/payments/contents/CODEOWNERS"))
                .willReturn(okJson("{\"content\": \"" + encoded + "\"}")));

        var provider = new GitHubProvider();
        var repo = new ScmRepository("1", "payments", "acme/payments", "main", false, null, null);
        OwnershipMap map = provider.resolveOwnership(config("acme"), repo);

        assertThat(map.pathOwners()).containsKey("*");
        assertThat(map.pathOwners()).containsKey("/src");
        assertThat(map.ownerTeams()).contains("@acme/platform", "@acme/backend");
    }
}
