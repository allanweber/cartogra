package io.cartogra.ingestion.infrastructure.scm.github;

import io.cartogra.ingestion.domain.CommitInfo;
import io.cartogra.ingestion.domain.OwnershipMap;
import io.cartogra.ingestion.domain.ScmConnectionConfig;
import io.cartogra.ingestion.infrastructure.scm.ScmProvider;
import io.cartogra.ingestion.domain.ScmRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class GitHubProvider implements ScmProvider {

    /** Events that warrant a re-sync; everything else (e.g. {@code ping}) is ignored. */
    private static final Set<String> RELEVANT_EVENTS = Set.of(
            "push",           // branch pushed
            "pull_request",   // PR opened / merged / closed
            "repository",     // repo renamed, archived, transferred
            "create",         // branch or tag created
            "delete",         // branch or tag deleted
            "member",         // collaborator added / removed (affects ownership resolution)
            "team"            // org team membership changed (affects @org/team in CODEOWNERS)
    );

    @Override
    public String providerType() {
        return "github";
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, Map<String, String> headers, @Nullable String secret) {
        if (secret == null || secret.isBlank()) {
            return true;
        }
        String signature = headers.get("X-Hub-Signature-256");
        if (signature == null || signature.isBlank()) {
            return false;
        }
        String expected = "sha256=" + hmacSha256Hex(secret, rawBody);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isRelevantWebhookEvent(Map<String, String> headers, String body) {
        String event = headers.get("X-GitHub-Event");
        return event != null && RELEVANT_EVENTS.contains(event);
    }

    private static String hmacSha256Hex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
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
                        Boolean.TRUE.equals(r.get("archived")),
                        Optional.ofNullable(r.get("description")).map(Object::toString).orElse(null),
                        Optional.ofNullable(r.get("html_url")).map(Object::toString).orElse(null)
                ))
                .toList();
    }

    @Override
    public Optional<CommitInfo> getLastCommit(ScmConnectionConfig config, ScmRepository repository) {
        String[] parts = repository.fullPath().split("/", 2);
        String owner = parts[0];
        String repo = parts.length > 1 ? parts[1] : parts[0];
        var client = new GitHubRestClient(config);
        return client.getLastCommit(owner, repo);
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
            throw new io.cartogra.ingestion.domain.exception.ScmProviderException(
                    "github", "Missing required config key: " + key);
        }
        return value.toString();
    }
}
