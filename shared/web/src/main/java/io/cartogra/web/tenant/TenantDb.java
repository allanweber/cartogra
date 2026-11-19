package io.cartogra.web.tenant;

/**
 * Turns off the RLS tenant filter for the code in the scope. Use it only where the code must read more
 * than one tenant on purpose (schedulers) or before the tenant is known (login, webhook lookup).
 */
public final class TenantDb {

    private TenantDb() {
    }

    public static <R, X extends Throwable> R asSystem(ScopedValue.CallableOp<? extends R, X> op) throws X {
        TenantContext.requireSameScopeInTransaction(null, true);
        return ScopedValue.where(TenantContext.SYSTEM, true).call(op);
    }

    public static void runAsSystem(Runnable op) {
        TenantContext.requireSameScopeInTransaction(null, true);
        ScopedValue.where(TenantContext.SYSTEM, true).run(op);
    }
}
