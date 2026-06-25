package io.cartogra.ingestion.infrastructure.scm.azuredevops;

import io.cartogra.ingestion.domain.CommitInfo;
import io.cartogra.ingestion.domain.ScmConnectionConfig;
import io.cartogra.ingestion.domain.exception.ScmProviderException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class AzureDevOpsRestClient {

    private static final String DEFAULT_BASE_URL = "https://dev.azure.com";

    private final RestClient restClient;
    private final String organization;

    AzureDevOpsRestClient(ScmConnectionConfig config) {
        this.organization = AzureDevOpsAuthHelper.requiredString(config, "organization");
        String baseUrl = Optional.ofNullable(config.config().get("apiBaseUrl"))
                .map(Object::toString)
                .orElse(DEFAULT_BASE_URL);
        String authHeader = AzureDevOpsAuthHelper.buildAuthorizationHeader(config);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", authHeader)
                .build();
    }

    List<String> listProjects() {
        Map<String, Object> response = restClient.get()
                .uri("/{org}/_apis/projects?api-version=7.1", organization)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new ScmProviderException("azuredevops",
                            "AzDO API error listing projects: " + res.getStatusCode());
                })
                .body(new ParameterizedTypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("value");
        return items == null ? List.of()
                : items.stream().map(p -> String.valueOf(p.get("name"))).toList();
    }

    List<Map<String, Object>> listRepositories(String project) {
        Map<String, Object> response = restClient.get()
                .uri("/{org}/{project}/_apis/git/repositories?api-version=7.1", organization, project)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new ScmProviderException("azuredevops",
                            "AzDO API error listing repos in project " + project + ": " + res.getStatusCode());
                })
                .body(new ParameterizedTypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("value");
        return items == null ? List.of() : items;
    }

    Optional<String> getFileContents(String project, String repoId, String filePath) {
        try {
            String content = restClient.get()
                    .uri("/{org}/{project}/_apis/git/repositories/{repoId}/items?path={path}&api-version=7.1",
                            organization, project, repoId, filePath)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new FileNotFoundException();
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new ScmProviderException("azuredevops",
                                "AzDO API error fetching file: " + res.getStatusCode());
                    })
                    .body(String.class);
            return Optional.ofNullable(content);
        } catch (FileNotFoundException _) {
            return Optional.empty();
        }
    }

    Optional<CommitInfo> getLastCommit(String project, String repoName) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/{org}/{project}/_apis/git/repositories/{repo}/commits?$top=1&api-version=7.1",
                            organization, project, repoName)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new FileNotFoundException();
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new ScmProviderException("azuredevops",
                                "AzDO API error fetching commits: " + res.getStatusCode());
                    })
                    .body(new ParameterizedTypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = response != null
                    ? (List<Map<String, Object>>) response.get("value") : null;
            if (items == null || items.isEmpty()) return Optional.empty();
            Map<String, Object> first = items.get(0);
            String sha = Optional.ofNullable(first.get("commitId")).map(Object::toString).orElse(null);
            String date = Optional.ofNullable(first.get("author"))
                    .map(a -> ((Map<?, ?>) a).get("date"))
                    .map(Object::toString)
                    .orElse(null);
            if (sha == null || date == null) return Optional.empty();
            return Optional.of(new CommitInfo(Instant.parse(date), sha));
        } catch (FileNotFoundException _) {
            return Optional.empty();
        }
    }

    private static final class FileNotFoundException extends RuntimeException {
        FileNotFoundException() { super(null, null, true, false); }
    }
}
