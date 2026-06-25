package io.cartogra.ingestion.infrastructure.scm.azuredevops;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
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

class AzureDevOpsProviderTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private ScmConnectionConfig config() {
        return new ScmConnectionConfig(
                UUID.randomUUID(), UUID.randomUUID(), "azuredevops",
                Map.of("pat", "test-pat", "organization", "contoso",
                        "apiBaseUrl", wireMock.baseUrl()));
    }

    @Test
    void listRepositories_returnsReposAcrossProjects() {
        wireMock.stubFor(get(urlPathEqualTo("/contoso/_apis/projects"))
                .willReturn(okJson("""
                        {"value": [{"name": "Platform"}, {"name": "Mobile"}], "count": 2}
                        """)));
        wireMock.stubFor(get(urlPathEqualTo("/contoso/Platform/_apis/git/repositories"))
                .willReturn(okJson("""
                        {"value": [{"id": "r1", "name": "payments-api",
                          "defaultBranch": "refs/heads/main"}], "count": 1}
                        """)));
        wireMock.stubFor(get(urlPathEqualTo("/contoso/Mobile/_apis/git/repositories"))
                .willReturn(okJson("""
                        {"value": [{"id": "r2", "name": "mobile-app",
                          "defaultBranch": "refs/heads/develop"}], "count": 1}
                        """)));

        var provider = new AzureDevOpsProvider();
        List<ScmRepository> repos = provider.listRepositories(config());

        assertThat(repos).hasSize(2);
        assertThat(repos).extracting(ScmRepository::name)
                .containsExactlyInAnyOrder("payments-api", "mobile-app");
        assertThat(repos.get(0).defaultBranch()).doesNotContain("refs/heads/");
    }

    @Test
    void getFileContents_returnsContent() {
        wireMock.stubFor(get(urlPathEqualTo("/contoso/Platform/_apis/git/repositories/payments-api/items"))
                .willReturn(ok("* [Platform Team]\n")));

        var provider = new AzureDevOpsProvider();
        Optional<String> content = provider.getFileContents(config(), "Platform/payments-api", "CODEOWNERS");

        assertThat(content).isPresent();
        assertThat(content.get()).contains("[Platform Team]");
    }

    @Test
    void getFileContents_returnsEmptyWhenNotFound() {
        wireMock.stubFor(get(urlPathEqualTo("/contoso/Platform/_apis/git/repositories/payments-api/items"))
                .willReturn(notFound()));

        var provider = new AzureDevOpsProvider();
        Optional<String> content = provider.getFileContents(config(), "Platform/payments-api", "CODEOWNERS");

        assertThat(content).isEmpty();
    }
}
