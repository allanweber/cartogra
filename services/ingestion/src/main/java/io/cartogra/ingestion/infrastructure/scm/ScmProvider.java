package io.cartogra.ingestion.infrastructure.scm;

import io.cartogra.ingestion.domain.CommitInfo;
import io.cartogra.ingestion.domain.OwnershipMap;
import io.cartogra.ingestion.domain.ScmConnectionConfig;
import io.cartogra.ingestion.domain.ScmRepository;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ScmProvider {

    String providerType();

    List<ScmRepository> listRepositories(ScmConnectionConfig config);

    Optional<String> getFileContents(ScmConnectionConfig config, String repoPath, String filePath);

    Optional<CommitInfo> getLastCommit(ScmConnectionConfig config, ScmRepository repository);

    OwnershipMap resolveOwnership(ScmConnectionConfig config, ScmRepository repository);

    /**
     * Verify an inbound webhook request. Headers are case-insensitive. When no secret is
     * configured, providers should accept the request. Default: accept (override to enforce).
     */
    default boolean verifyWebhookSignature(byte[] rawBody, Map<String, String> headers, @Nullable String secret) {
        return true;
    }

    /**
     * Whether the webhook event should trigger a sync (e.g. a push). Filters out noise such as
     * GitHub {@code ping}. Default: relevant (override to filter).
     */
    default boolean isRelevantWebhookEvent(Map<String, String> headers, String body) {
        return true;
    }
}
