package edu.zsc.ai.plugin.model.sql;

/**
 * What kind of completion candidates the cursor position expects.
 * Determined by the dialect's completion parser, consumed by the application
 * layer to decide which metadata to query.
 */
public enum CompletionScope {
    /** Cursor is inside a string literal or comment: no candidates. */
    NONE,
    /** Start of a statement or a position where only keywords/functions make sense. */
    KEYWORD,
    PROCEDURAL,
    VARIABLE,
    /** A table or view (or schema qualifier) is expected, e.g. after FROM / JOIN / INTO / UPDATE. */
    TABLE,
    JOIN,
    INSERT_COLUMN,
    UPDATE_COLUMN,
    FUNCTION,
    PROCEDURE,
    /** A value expression is expected: columns, aliases, keywords and functions. */
    COLUMN,
    /** Right after a qualifier dot (alias., schema., schema.table.): resolve the qualifier. */
    QUALIFIED
}
