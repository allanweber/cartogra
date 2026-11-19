package io.cartogra.contract.domain;

import java.util.Locale;

/** A rule of the breaking-change engine; each rule has a fixed verdict. */
public enum CompatibilityRule {
    PATH_REMOVED(true),
    PATH_ADDED(false),
    OPERATION_REMOVED(true),
    OPERATION_ADDED(false),
    RESPONSE_FIELD_REMOVED(true),
    RESPONSE_FIELD_ADDED(false),
    RESPONSE_FIELD_TYPE_CHANGED(true),
    RESPONSE_ENUM_VALUE_ADDED(true),
    RESPONSE_ENUM_VALUE_REMOVED(false),
    REQUEST_FIELD_TYPE_CHANGED(true),
    REQUEST_FIELD_REQUIRED_ADDED(true),
    REQUEST_FIELD_OPTIONAL_ADDED(false),
    REQUEST_FIELD_REMOVED(false),
    REQUEST_FIELD_REQUIRED_REMOVED(false),
    REQUEST_ENUM_VALUE_REMOVED(true),
    REQUEST_ENUM_VALUE_ADDED(false),
    PARAMETER_TYPE_CHANGED(true),
    PARAMETER_REQUIRED_ADDED(true),
    PARAMETER_OPTIONAL_ADDED(false),
    PARAMETER_REQUIRED_REMOVED(false),
    PARAMETER_ENUM_VALUE_REMOVED(true),
    PARAMETER_ENUM_VALUE_ADDED(false);

    private final boolean breaking;

    CompatibilityRule(boolean breaking) {
        this.breaking = breaking;
    }

    public boolean breaking() {
        return breaking;
    }

    /** The rule id in the API, e.g. {@code path-removed}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
