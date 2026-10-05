package edu.zsc.ai.plugin.capability;

import edu.zsc.ai.plugin.model.sql.CompletionContext;
import edu.zsc.ai.plugin.model.sql.CompletionFunction;

import java.util.List;
import java.util.Set;

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
