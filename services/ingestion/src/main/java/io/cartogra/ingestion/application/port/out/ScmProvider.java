package io.cartogra.ingestion.application.port.out;

import java.util.List;
import java.util.Optional;

public interface ScmProvider {

    String providerType();

    List<ScmRepository> listRepositories(ScmConnectionConfig config);

    Optional<String> getFileContents(ScmConnectionConfig config, String repoPath, String filePath);

    OwnershipMap resolveOwnership(ScmConnectionConfig config, ScmRepository repository);
}
