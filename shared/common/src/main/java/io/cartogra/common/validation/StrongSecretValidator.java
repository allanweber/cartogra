package io.cartogra.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public class StrongSecretValidator implements ConstraintValidator<StrongSecret, String> {

    private static final List<String> PUBLISHED_DEFAULTS = List.of(
            "change-me", "changeme", "change_me", "placeholder", "replace-with", "replace_me",
            "demo-admin-pw1", "your-secret", "example");

    private int minBytes;

    @Override
    public void initialize(StrongSecret annotation) {
        this.minBytes = annotation.minBytes();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null || value.isEmpty()) return true;
        if (value.getBytes(StandardCharsets.UTF_8).length < minBytes) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return PUBLISHED_DEFAULTS.stream().noneMatch(lower::contains);
    }
}
