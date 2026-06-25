package io.cartogra.ingestion.infrastructure.scm.azuredevops;

import io.cartogra.ingestion.domain.CommitInfo;
import io.cartogra.ingestion.domain.OwnershipMap;
import io.cartogra.ingestion.domain.ScmConnectionConfig;
import io.cartogra.ingestion.infrastructure.scm.ScmProvider;
import io.cartogra.ingestion.domain.ScmRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class AzureDevOpsProvider implements ScmProvider {

    private static final Set<String> RELEVANT_EVENTS = Set.of(
            "git.push",
            "git.pullrequest.created",
            "git.pullrequest.updated",
            "git.pullrequest.merged",
            "git.ref.created",
            "git.ref.deleted"
    );

    @Override
    public String providerType() {
        return "azuredevops";
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, Map<String, String> headers, @Nullable String secret) {
        // Azure DevOps service hooks authenticate with HTTP Basic auth; the configured
        // secret is the "user:password" pair the hook was registered with.
        if (secret == null || secret.isBlank()) {
            return true;
        }
        String auth = headers.get("Authorization");
        if (auth == null || !auth.startsWith("Basic ")) {
            return false;
        }
        String expected = "Basic " + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                auth.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isRelevantWebhookEvent(Map<String, String> headers, String body) {
        // eventType is a top-level string field in the Azure DevOps service hook payload.
        return RELEVANT_EVENTS.stream().anyMatch(body::contains);
    }

    @Override
    public List<ScmRepository> listRepositories(ScmConnectionConfig config) {
        var client = new AzureDevOpsRestClient(config);
        List<String> projects = client.listProjects();
        List<ScmRepository> repositories = new ArrayList<>();
        for (String project : projects) {
            List<Map<String, Object>> raw = client.listRepositories(project);
            raw.stream()
                    .map(r -> new ScmRepository(
                            String.valueOf(r.get("id")),
                            String.valueOf(r.get("name")),
                            project + "/" + r.get("name"),
                            Optional.ofNullable(r.get("defaultBranch"))
                                    .map(Object::toString)
                                    .map(b -> b.replace("refs/heads/", ""))
                                    .orElse("main"),
                            false,
                            null,
                            Optional.ofNullable(r.get("remoteUrl")).map(Object::toString).orElse(null)
                    ))
                    .forEach(repositories::add);
        }
        return repositories;
    }

    @Override
    public Optional<CommitInfo> getLastCommit(ScmConnectionConfig config, ScmRepository repository) {
        String[] parts = repository.fullPath().split("/", 2);
        String project = parts[0];
        String repoName = parts.length > 1 ? parts[1] : parts[0];
        var client = new AzureDevOpsRestClient(config);
        return client.getLastCommit(project, repoName);
    }

    @Override
    public Optional<String> getFileContents(ScmConnectionConfig config, String repoPath, String filePath) {
        String[] parts = repoPath.split("/", 2);
        String project = parts[0];
        String repoName = parts.length > 1 ? parts[1] : parts[0];
        var client = new AzureDevOpsRestClient(config);
        return client.getFileContents(project, repoName, filePath);
    }

    @Override
    public OwnershipMap resolveOwnership(ScmConnectionConfig config, ScmRepository repository) {
        String[] parts = repository.fullPath().split("/", 2);
        String project = parts[0];
        String repoName = parts.length > 1 ? parts[1] : parts[0];
        var client = new AzureDevOpsRestClient(config);

        Optional<String> content = client.getFileContents(project, repoName, "CODEOWNERS")
                .or(() -> client.getFileContents(project, repoName, ".azuredevops/CODEOWNERS"));

        List<String> ownerTeams = new ArrayList<>();
        Map<String, List<String>> pathOwners = new HashMap<>();

        content.ifPresent(text -> text.lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .forEach(line -> {
                    String[] tokenParts = line.trim().split("\\s+");
                    String path = tokenParts[0];
                    List<String> owners = tokenParts.length > 1
                            ? List.of(java.util.Arrays.copyOfRange(tokenParts, 1, tokenParts.length))
                            : List.of();
                    pathOwners.put(path, owners);
                    owners.stream()
                            .filter(o -> o.startsWith("[") || o.contains("\\"))
                            .forEach(team -> {
                                if (!ownerTeams.contains(team)) ownerTeams.add(team);
                            });
                }));

        return new OwnershipMap(ownerTeams, pathOwners);
    }
}
