package io.cartogra.ingestion.infrastructure.scm;

import java.util.Optional;

/**
 * Reduces a provider-specific CODEOWNERS handle ({@code @org/team}, {@code @team}, {@code [Project]\Team},
 * {@code [Team]}) to the bare team name Registry matches against. Email addresses are never teams.
 */
public final class CodeownersHandles {

    private CodeownersHandles() {
    }

    public static Optional<String> teamName(String handle) {
        String trimmed = handle.strip();
        if (trimmed.indexOf('@') > 0) {
            return Optional.empty();
        }
        String name = trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
        int separator = Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/'));
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        if (name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1);
        }
        name = name.strip();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }
}
