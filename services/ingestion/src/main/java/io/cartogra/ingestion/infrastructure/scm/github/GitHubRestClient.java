package io.cartogra.ingestion.infrastructure.scm.github;

import io.cartogra.ingestion.application.port.out.ScmConnectionConfig;
import io.cartogra.ingestion.application.port.out.ScmProviderException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

class GitHubRestClient {

    private static final String DEFAULT_BASE_URL = "https://api.github.com";

    private final RestClient restClient;

    GitHubRestClient(ScmConnectionConfig config) {
        String baseUrl = Optional.ofNullable(config.config().get("apiBaseUrl"))
                .map(Object::toString)
                .orElse(DEFAULT_BASE_URL);
        String token = requiredString(config, "token");

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    List<Map<String, Object>> listRepositories(String org) {
        return restClient.get()
                .uri("/orgs/{org}/repos?per_page=100&type=all", org)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new ScmProviderException("github",
                            "GitHub API error listing repos: " + res.getStatusCode());
                })
                .body(new org.springframework.core.ParameterizedTypeReference<>() {});
    }

    Optional<String> getFileContents(String owner, String repo, String filePath) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, filePath)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new FileNotFoundException();
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new ScmProviderException("github",
                                "GitHub API error fetching file: " + res.getStatusCode());
                    })
                    .body(new org.springframework.core.ParameterizedTypeReference<>() {});
            return Optional.ofNullable(response)
                    .map(r -> r.get("content"))
                    .map(Object::toString)
                    .map(GitHubRestClient::decodeBase64);
        } catch (FileNotFoundException _) {
            return Optional.empty();
        }
    }

    List<Map<String, Object>> getCodeOwners(String owner, String repo) {
        Optional<String> content = getFileContents(owner, repo, "CODEOWNERS")
                .or(() -> getFileContents(owner, repo, ".github/CODEOWNERS"))
                .or(() -> getFileContents(owner, repo, "docs/CODEOWNERS"));
        return content.map(GitHubRestClient::parseCodeOwners).orElse(List.of());
    }

    private static String decodeBase64(String encoded) {
        String cleaned = encoded.replace("\n", "").replace(" ", "");
        return new String(java.util.Base64.getDecoder().decode(cleaned));
    }

    private static List<Map<String, Object>> parseCodeOwners(String content) {
        return content.lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(line -> {
                    String[] parts = line.trim().split("\\s+");
                    String path = parts[0];
                    List<String> owners = parts.length > 1
                            ? List.of(java.util.Arrays.copyOfRange(parts, 1, parts.length))
                            : List.of();
                    return (Map<String, Object>) Map.of("path", path, "owners", owners);
                })
                .toList();
    }

    private static String requiredString(ScmConnectionConfig config, String key) {
        Object value = config.config().get(key);
        if (value == null || value.toString().isBlank()) {
            throw new ScmProviderException("github", "Missing required config key: " + key);
        }
        return value.toString();
    }

    private static final class FileNotFoundException extends RuntimeException {
        FileNotFoundException() { super(null, null, true, false); }
    }
}
