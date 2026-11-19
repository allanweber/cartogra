package io.cartogra.test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the test method without the system scope of {@link TenantScopeExtension}: the test then sees the
 * scope that production code sets. Use it to prove fail-closed RLS and the scope of an entry point.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface NoTenantScope {
}
