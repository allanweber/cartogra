package io.cartogra.gateway.infrastructure.oauth;

public interface OAuthProvider {
    String providerName();
    String buildAuthorizationUri(String state, String redirectUri);
    String exchangeCodeForAccessToken(String code, String redirectUri);
    OAuthProfile fetchProfile(String accessToken);
}
