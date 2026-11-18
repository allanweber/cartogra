package io.cartogra.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A secret from configuration that is long enough and is not a published placeholder. Null and empty mean "not
 * set" and are valid, so combine with {@code @NotEmpty} when the value is required. Startup fails when a bound property does not match.
 */
@Documented
@Constraint(validatedBy = StrongSecretValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongSecret {
    int minBytes();
    String message() default "must be at least {minBytes} bytes and must not be a placeholder or a published default";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
