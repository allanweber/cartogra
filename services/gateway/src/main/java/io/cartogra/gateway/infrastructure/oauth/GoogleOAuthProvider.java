package io.cartogra.gateway.infrastructure.oauth;

import io.cartogra.gateway.config.OAuthConfig;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Component
public class GoogleOAuthProvider implements OAuthProvider {

    private static final String AUTH_URI = "https://accounts.google.com/o/oauth2/v2/auth";

    private final OAuthConfig.ProviderConfig config;
    private final WebClient webClient;

    public GoogleOAuthProvider(OAuthConfig oauthConfig, WebClient.Builder builder) {
        this.config = oauthConfig.google();
        this.webClient = builder.build();
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
        Map<?, ?> response = webClient.post()
            .uri(config.tokenUri())
            .bodyValue(Map.of(
                "code", code,
                "client_id", config.clientId(),
                "client_secret", config.clientSecret(),
                "redirect_uri", redirectUri,
                "grant_type", "authorization_code"
            ))
            .retrieve()
            .bodyToMono(Map.class)
            .block();
        if (response == null || !response.containsKey("access_token")) {
            throw new IllegalStateException("Failed to exchange Google auth code");
        }
        return (String) response.get("access_token");
    }

    @Override
    public OAuthProfile fetchProfile(String accessToken) {
        Map<?, ?> userinfo = webClient.get()
            .uri(config.userinfoUri())
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .bodyToMono(Map.class)
            .block();
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
