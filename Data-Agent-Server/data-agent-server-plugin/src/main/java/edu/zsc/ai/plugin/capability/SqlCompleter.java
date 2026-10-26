package edu.zsc.ai.plugin.capability;

import edu.zsc.ai.plugin.model.sql.*;

import java.util.List;
import java.util.Set;
import java.sql.Connection;

/**
 * Dialect-aware completion support. The implementation must tolerate
 * incomplete SQL: it analyzes the text before the cursor with a lenient,
 * token-based strategy instead of requiring a fully valid statement.
 */
public interface SqlCompleter {

    /**
     * Analyze the completion point without hitting the database.
     *
     * @param sql          the full editor content, possibly syntactically invalid
     * @param cursorOffset UTF-16 offset of the cursor inside {@code sql}
     * @return the completion context, never null
     */
    CompletionContext analyzeCompletionContext(String sql, int cursorOffset);

    /**
     * Static keyword list of the dialect, in the case the editor should insert.
     */
    List<String> completionKeywords();

    /**
     * Static built-in function names of the dialect, without parentheses.
     */
    List<String> completionFunctions();

    default List<CompletionFunction> functionTemplates() {
        return completionFunctions().stream().map(name ->
                new CompletionFunction(name, name + "(${1:})", name + "(…)", true)).toList();
    }

    default CompletionFunctionCatalog functionCatalog(Connection connection) {
        return new CompletionFunctionCatalog(functionTemplates(), null, false);
    }

    default List<String> completionPackages(Connection connection, String schema) { return List.of(); }

    default List<CompletionRoutine> completionPackageMembers(Connection connection, String schema, String packageName) { return List.of(); }

    default List<CompletionSynonym> completionSynonyms(Connection connection, String schema) { return List.of(); }

    default String completionSchema(Connection connection, String schema) {
        if (schema != null && !schema.isBlank()) return schema;
        try { return connection.getSchema(); } catch (java.sql.SQLException e) { return schema; }
    }

    /**
     * Words that must be quoted when used as identifiers in this dialect.
     * Defaults to the keyword list; dialects should override with their full
     * reserved-word set.
     */
    default Set<String> reservedWords() {
        return Set.copyOf(completionKeywords());
    }

    /** Render one resolved name; metadata spelling and quoted aliases must survive. */
    default String identifierInsertText(String name, boolean quoted) {
        if (this instanceof SqlIdentifierEscaper escaper) {
            return escaper.quoteIdentifier(name);
        }
        return name;
    }
}
