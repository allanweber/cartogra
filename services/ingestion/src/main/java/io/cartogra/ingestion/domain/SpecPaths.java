package io.cartogra.ingestion.domain;

import java.util.List;

/** The OpenAPI probe paths of a tenant: the built-in defaults and the custom paths of the admin. */
public record SpecPaths(List<String> defaults, List<String> custom) {

    public SpecPaths {
        defaults = List.copyOf(defaults);
        custom = List.copyOf(custom);
    }
}
