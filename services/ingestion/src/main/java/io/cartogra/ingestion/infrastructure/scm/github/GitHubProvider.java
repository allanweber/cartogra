package io.cartogra.ingestion.infrastructure.scm.github;

import io.cartogra.ingestion.application.port.out.OwnershipMap;
import io.cartogra.ingestion.application.port.out.ScmConnectionConfig;
import io.cartogra.ingestion.application.port.out.ScmProvider;
import io.cartogra.ingestion.application.port.out.ScmRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class GitHubProvider implements ScmProvider {

    @Override
    public String providerType() {
        return "github";
    }

    @Override
    public List<ScmRepository> listRepositories(ScmConnectionConfig config) {
        String org = requiredString(config, "org");
        var client = new GitHubRestClient(config);
        List<Map<String, Object>> raw = client.listRepositories(org);
        return raw.stream()
                .map(r -> new ScmRepository(
                        String.valueOf(r.get("id")),
                        String.valueOf(r.get("name")),
                        String.valueOf(r.get("full_name")),
                        Optional.ofNullable(r.get("default_branch")).map(Object::toString).orElse("main"),
                        Boolean.TRUE.equals(r.get("archived"))
                ))
                .toList();
    }

    @Override
    public Optional<String> getFileContents(ScmConnectionConfig config, String repoPath, String filePath) {
        String[] parts = repoPath.split("/", 2);
        String owner = parts[0];
        String repo = parts.length > 1 ? parts[1] : parts[0];
        var client = new GitHubRestClient(config);
        return client.getFileContents(owner, repo, filePath);
    }

    @Override
    public OwnershipMap resolveOwnership(ScmConnectionConfig config, ScmRepository repository) {
        String[] parts = repository.fullPath().split("/", 2);
        String owner = parts[0];
        String repo = parts.length > 1 ? parts[1] : parts[0];
        var client = new GitHubRestClient(config);
        List<Map<String, Object>> entries = client.getCodeOwners(owner, repo);

        List<String> ownerTeams = new ArrayList<>();
        Map<String, List<String>> pathOwners = new HashMap<>();

        for (Map<String, Object> entry : entries) {
            String path = String.valueOf(entry.get("path"));
            @SuppressWarnings("unchecked")
            List<String> owners = (List<String>) entry.get("owners");
            pathOwners.put(path, owners);
            owners.stream()
                    .filter(o -> o.startsWith("@") && o.contains("/"))
                    .forEach(team -> {
                        if (!ownerTeams.contains(team)) ownerTeams.add(team);
                    });
        }

        return new OwnershipMap(ownerTeams, pathOwners);
    }

    private static String requiredString(ScmConnectionConfig config, String key) {
        Object value = config.config().get(key);
        if (value == null || value.toString().isBlank()) {
            throw new io.cartogra.ingestion.application.port.out.ScmProviderException(
                    "github", "Missing required config key: " + key);
        }
        return value.toString();
    }
}
