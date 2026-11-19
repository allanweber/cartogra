package io.cartogra.test;

import io.cartogra.web.tenant.TenantDb;
import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Runs test code in the RLS system scope, so seed rows and direct service calls see all tenants. A MockMvc
 * request still binds its own tenant in the filter, and listeners and schedulers run on other threads, so
 * those paths keep the production scope. Loaded by autodetection for each test; {@link NoTenantScope} turns
 * it off for the test method. Awaitility polls on its own thread by default, which has no scope, so this
 * extension makes it poll on the test thread.
 */
public class TenantScopeExtension implements InvocationInterceptor, BeforeAllCallback {

    private static final String AWAITILITY = "org.awaitility.Awaitility";

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        Class<?> awaitility;
        try {
            awaitility = Class.forName(AWAITILITY, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException _) {
            return;
        }
        awaitility.getMethod("pollInSameThread").invoke(null);
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        if (optedOut(extensionContext)) {
            invocation.proceed();
            return;
        }
        inSystemScope(invocation);
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        if (optedOut(extensionContext)) {
            invocation.proceed();
            return;
        }
        inSystemScope(invocation);
    }

    @Override
    public void interceptBeforeAllMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        inSystemScope(invocation);
    }

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        inSystemScope(invocation);
    }

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        inSystemScope(invocation);
    }

    @Override
    public void interceptAfterAllMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        inSystemScope(invocation);
    }

    private static boolean optedOut(ExtensionContext context) {
        return context.getTestMethod().map(m -> AnnotationSupport.isAnnotated(m, NoTenantScope.class)).orElse(false)
                || context.getTestClass().map(c -> AnnotationSupport.isAnnotated(c, NoTenantScope.class)).orElse(false);
    }

    private static void inSystemScope(Invocation<Void> invocation) throws Throwable {
        TenantDb.asSystem(invocation::proceed);
    }
}
