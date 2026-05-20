package io.cartogra.gateway.infrastructure.oauth;

import io.cartogra.gateway.config.OAuthConfig;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Component
public class GitHubOAuthProvider implements OAuthProvider {

    private static final String AUTH_URI = "https://github.com/login/oauth/authorize";

    private final OAuthConfig.ProviderConfig config;
    private final RestClient restClient;

    public GitHubOAuthProvider(OAuthConfig oauthConfig, RestClient.Builder builder) {
        this.config = oauthConfig.github();
        this.restClient = builder.build();
    }

    @Override
    public String providerName() {
        return "github";
    }

    @Override
    public String buildAuthorizationUri(String state, String redirectUri) {
        return UriComponentsBuilder.fromUriString(AUTH_URI)
            .queryParam("client_id", config.clientId())
            .queryParam("redirect_uri", redirectUri)
            .queryParam("scope", "user:email")
            .queryParam("state", state)
            .build().toUriString();
    }

    @Override
    public String exchangeCodeForAccessToken(String code, String redirectUri) {
        Map<?, ?> response = restClient.post()
            .uri(config.tokenUri())
            .header("Accept", "application/json")
            .body(Map.of(
                "code", code,
                "client_id", config.clientId(),
                "client_secret", config.clientSecret(),
                "redirect_uri", redirectUri
            ))
            .retrieve()
            .body(Map.class);
        if (response == null || !response.containsKey("access_token")) {
            throw new IllegalStateException("Failed to exchange GitHub auth code");
        }
        return (String) response.get("access_token");
    }

    @Override
    public OAuthProfile fetchProfile(String accessToken) {
        Map<?, ?> user = restClient.get()
            .uri(config.userinfoUri())
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/vnd.github+json")
            .retrieve()
            .body(Map.class);
        if (user == null) {
            throw new IllegalStateException("Failed to fetch GitHub user profile");
        }
        String email = user.get("email") != null ? (String) user.get("email") : "";
        return new OAuthProfile(
            String.valueOf(user.get("id")),
            email,
            (String) user.get("name")
        );
    }
}
