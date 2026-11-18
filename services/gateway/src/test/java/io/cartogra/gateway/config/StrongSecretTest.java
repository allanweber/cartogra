package io.cartogra.gateway.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class StrongSecretTest {

    private static final String JWT_OK = "app.jwt.secret=ikLUD/yokL8nJI/BcwQ6GvbDq3pZMy3DTO5xHUMKiTw=";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Props.class)
            .withPropertyValues("app.jwt.access-token-expiry-seconds=900", "app.jwt.refresh-token-expiry-seconds=600");

    @Configuration
    @EnableConfigurationProperties({JwtConfig.class, TenantBootstrapProperties.class})
    static class Props {}

    @ParameterizedTest
    @ValueSource(strings = {
            "change-me-in-production-min-32-bytes",
            "CHANGEME-CHANGEME-CHANGEME-CHANGEME-1",
            "placeholder-placeholder-placeholder-1",
            "too-short-but-random-Qx7"
    })
    void startupFailsForAWeakOrPublishedJwtSecret(String secret) {
        runner.withPropertyValues("app.jwt.secret=" + secret)
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void startupSucceedsForARandomJwtSecret() {
        runner.withPropertyValues(JWT_OK).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Demo-Admin-pw1", "short-pw1"})
    void startupFailsForAPublishedOrShortBootstrapAdminPassword(String password) {
        runner.withPropertyValues(JWT_OK, "app.bootstrap.tenant.admin-password=" + password)
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void startupFailsWithoutAJwtSecret() {
        runner.withPropertyValues("app.jwt.secret=").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void emptyBootstrapAdminPasswordMeansNotSet() {
        runner.withPropertyValues(JWT_OK, "app.bootstrap.tenant.admin-password=")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void startupSucceedsWithoutABootstrapAdminPassword() {
        runner.withPropertyValues(JWT_OK).run(ctx ->
                assertThat(ctx.getBean(TenantBootstrapProperties.class).adminPassword()).isNull());
    }

    @Test
    void startupSucceedsForAStrongBootstrapAdminPassword() {
        runner.withPropertyValues(JWT_OK, "app.bootstrap.tenant.admin-password=Rk2v-9qLm-Tz4w")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
