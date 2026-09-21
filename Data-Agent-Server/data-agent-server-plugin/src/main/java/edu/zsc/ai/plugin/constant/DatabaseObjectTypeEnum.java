package edu.zsc.ai.plugin.constant;

import java.util.EnumSet;
import java.util.Locale;
import java.util.stream.Collectors;

public enum DatabaseObjectTypeEnum {

    DATABASE("database"),
    TABLE("table"),
    VIEW("view"),
    FUNCTION("function"),
    PROCEDURE("procedure"),
    TRIGGER("trigger");

    private static final EnumSet<DatabaseObjectTypeEnum> QUERYABLE_TYPES = EnumSet.of(
            TABLE, VIEW, FUNCTION, PROCEDURE, TRIGGER
    );

    private final String value;

    DatabaseObjectTypeEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * Parse a raw string (case-insensitive) into a queryable object type (TABLE/VIEW/FUNCTION/PROCEDURE/TRIGGER).
     * Throws IllegalArgumentException if blank, unrecognized, or not a queryable type (e.g. DATABASE).
     */
    public static DatabaseObjectTypeEnum parseQueryable(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw new IllegalArgumentException("objectType must not be blank");
        }
        DatabaseObjectTypeEnum type;
        try {
            type = valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unsupported objectType: " + rawType + ". Allowed values: " + queryableTypeNames());
        }
        if (!QUERYABLE_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "Unsupported objectType: " + rawType + ". Allowed values: " + queryableTypeNames());
        }
        return type;
    }

    public static String queryableTypeNames() {
        return QUERYABLE_TYPES.stream().map(Enum::name).collect(Collectors.joining(", "));
    }
}
