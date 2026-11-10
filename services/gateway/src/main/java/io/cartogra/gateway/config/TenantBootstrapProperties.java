package io.cartogra.gateway.config;

import jakarta.validation.constraints.Email;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * First tenant and admin of an install, created at startup. Needed when self-registration is off, since
 * nothing else can create a tenant then. Off when {@code tenantId} is unset.
 */
@Validated
@ConfigurationProperties(prefix = "app.bootstrap.tenant")
public record TenantBootstrapProperties(
    @Nullable UUID tenantId,
    @Nullable String name,
    @DefaultValue("free") String plan,
    @Nullable @Email String adminEmail,
    @Nullable String adminPassword,
    @DefaultValue("false") boolean publicSandbox
) {

    public boolean enabled() {
        return tenantId != null;
    }
}
