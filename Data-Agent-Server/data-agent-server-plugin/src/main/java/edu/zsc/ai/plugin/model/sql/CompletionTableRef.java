package edu.zsc.ai.plugin.model.sql;

/**
 * A table/view reference visible at the completion point.
 *
 * @param schema the explicit schema qualifier, or null when unqualified
 * @param name   the table or view name, never null
 */
public record CompletionTableRef(String schema, String name) {

    public static CompletionTableRef of(String schema, String name) {
        return new CompletionTableRef(
                (schema == null || schema.isBlank()) ? null : schema,
                name);
    }
}
