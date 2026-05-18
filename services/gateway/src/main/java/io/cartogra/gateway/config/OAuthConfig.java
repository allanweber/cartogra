package io.cartogra.gateway.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.oauth")
@Validated
public record OAuthConfig(
    ProviderConfig google,
    ProviderConfig github
) {
    public record ProviderConfig(
        @NotEmpty String clientId,
        @NotEmpty String clientSecret,
        @NotEmpty String redirectUri,
        @NotEmpty String tokenUri,
        @NotEmpty String userinfoUri
    ) {}
}
