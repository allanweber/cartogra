package io.cartogra.gateway.infrastructure.oauth;

import io.cartogra.gateway.config.OAuthConfig;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Component
public class GoogleOAuthProvider implements OAuthProvider {

    private static final String AUTH_URI = "https://accounts.google.com/o/oauth2/v2/auth";

    private final OAuthConfig.ProviderConfig config;
    private final RestClient restClient;

    public GoogleOAuthProvider(OAuthConfig oauthConfig, RestClient.Builder builder) {
        this.config = oauthConfig.google();
        this.restClient = builder.build();
    }

    @Override
    public String providerName() {
        return "google";
    }

    @Override
    public String buildAuthorizationUri(String state, String redirectUri) {
        return UriComponentsBuilder.fromUriString(AUTH_URI)
            .queryParam("client_id", config.clientId())
            .queryParam("redirect_uri", redirectUri)
            .queryParam("response_type", "code")
            .queryParam("scope", "openid email profile")
            .queryParam("state", state)
            .build().toUriString();
    }

    @Override
    public String exchangeCodeForAccessToken(String code, String redirectUri) {
        Map<?, ?> response = restClient.post()
            .uri(config.tokenUri())
            .body(Map.of(
                "code", code,
                "client_id", config.clientId(),
                "client_secret", config.clientSecret(),
                "redirect_uri", redirectUri,
                "grant_type", "authorization_code"
            ))
            .retrieve()
            .body(Map.class);
        if (response == null || !response.containsKey("access_token")) {
            throw new IllegalStateException("Failed to exchange Google auth code");
        }
        return (String) response.get("access_token");
    }

    @Override
    public OAuthProfile fetchProfile(String accessToken) {
        Map<?, ?> userinfo = restClient.get()
            .uri(config.userinfoUri())
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .body(Map.class);
        if (userinfo == null) {
            throw new IllegalStateException("Failed to fetch Google user profile");
        }
        return new OAuthProfile(
            (String) userinfo.get("sub"),
            (String) userinfo.get("email"),
            (String) userinfo.get("name")
        );
    }
}
