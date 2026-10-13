package edu.zsc.ai.plugin.model.sql;

import java.util.Locale;

/** A resolved SQL name. Quoted names retain their exact spelling. */
public record CompletionIdentifier(String name, boolean quoted) {
    public static CompletionIdentifier unquoted(String name) {
        return new CompletionIdentifier(name.toUpperCase(Locale.ROOT), false);
    }
}
