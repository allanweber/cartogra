package io.cartogra.ingestion.infrastructure.scm.azuredevops;

import io.cartogra.ingestion.application.port.out.ScmConnectionConfig;
import io.cartogra.ingestion.application.port.out.ScmProviderException;

import java.util.Base64;

final class AzureDevOpsAuthHelper {

    private AzureDevOpsAuthHelper() {}

    static String buildAuthorizationHeader(ScmConnectionConfig config) {
        Object pat = config.config().get("pat");
        if (pat != null && !pat.toString().isBlank()) {
            String encoded = Base64.getEncoder()
                    .encodeToString((":" + pat).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "Basic " + encoded;
        }
        throw new ScmProviderException("azuredevops",
                "No supported auth credential found in config (expected 'pat')");
    }

    static String requiredString(ScmConnectionConfig config, String key) {
        Object value = config.config().get(key);
        if (value == null || value.toString().isBlank()) {
            throw new ScmProviderException("azuredevops", "Missing required config key: " + key);
        }
        return value.toString();
    }
}
