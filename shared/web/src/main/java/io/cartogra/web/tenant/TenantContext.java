package io.cartogra.web.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The tenant that the current thread works for. {@link TenantAwareDataSource} copies it to each connection
 * that it gives out, so the Postgres RLS policies see it. Bind it before a transaction starts: an open
 * transaction already holds its connection, so a later binding cannot change what that connection sees.
 */
public final class TenantContext {

    static final ScopedValue<UUID> TENANT = ScopedValue.newInstance();
    static final ScopedValue<Boolean> SYSTEM = ScopedValue.newInstance();

    private TenantContext() {
    }

    public static <R, X extends Throwable> R callAs(UUID tenantId, ScopedValue.CallableOp<? extends R, X> op)
            throws X {
        Objects.requireNonNull(tenantId, "tenantId");
        requireSameScopeInTransaction(tenantId, false);
        return ScopedValue.where(TENANT, tenantId).where(SYSTEM, false).call(op);
    }

    public static void runAs(UUID tenantId, Runnable op) {
        Objects.requireNonNull(tenantId, "tenantId");
        requireSameScopeInTransaction(tenantId, false);
        ScopedValue.where(TENANT, tenantId).where(SYSTEM, false).run(op);
    }

    public static Optional<UUID> current() {
        return TENANT.isBound() && !isSystem() ? Optional.of(TENANT.get()) : Optional.empty();
    }

    static boolean isSystem() {
        return SYSTEM.isBound() && SYSTEM.get();
    }

    static void requireSameScopeInTransaction(@Nullable UUID tenantId, boolean system) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        if (isSystem() != system || !Objects.equals(current().orElse(null), tenantId)) {
            throw new IllegalStateException(
                    "Cannot change the tenant scope inside an open transaction: its connection is already bound."
                            + " Change the scope before the transaction starts.");
        }
    }
}
