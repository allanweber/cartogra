package io.cartogra.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public class HttpUrlValidator implements ConstraintValidator<HttpUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null || value.isEmpty()) return true;
        if (value.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) return false;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            String lower = scheme.toLowerCase(Locale.ROOT);
            return ("http".equals(lower) || "https".equals(lower))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (URISyntaxException _) {
            return false;
        }
    }
}
