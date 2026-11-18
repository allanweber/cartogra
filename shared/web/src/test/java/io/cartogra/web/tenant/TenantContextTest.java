package io.cartogra.web.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TenantContextTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void noScopeHasNoTenantAndNoSystem() {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(TenantContext.isSystem()).isFalse();
    }

    @Test
    void callAsBindsTheTenantOnlyInsideTheScope() {
        UUID seen = TenantContext.callAs(TENANT_A, () -> TenantContext.current().orElseThrow());

        assertThat(seen).isEqualTo(TENANT_A);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void asSystemHidesTheTenantAndSetsSystem() {
        TenantContext.runAs(TENANT_A, () -> TenantDb.runAsSystem(() -> {
            assertThat(TenantContext.isSystem()).isTrue();
            assertThat(TenantContext.current()).isEmpty();
        }));
    }

    @Test
    void tenantScopeInsideSystemScopeTurnsSystemOff() {
        TenantDb.runAsSystem(() -> TenantContext.runAs(TENANT_B, () -> {
            assertThat(TenantContext.isSystem()).isFalse();
            assertThat(TenantContext.current()).contains(TENANT_B);
        }));
    }

    @Test
    void changingTheScopeInsideATransactionFails() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> TenantDb.runAsSystem(() -> { }))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TenantContext.runAs(TENANT_A, () -> { }))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theSameScopeInsideATransactionIsAllowed() {
        TenantContext.runAs(TENANT_A, () -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TenantContext.runAs(TENANT_A, () -> assertThat(TenantContext.current()).contains(TENANT_A));
        });
    }
}
