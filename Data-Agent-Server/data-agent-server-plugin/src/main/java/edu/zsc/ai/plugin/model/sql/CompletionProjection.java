package edu.zsc.ai.plugin.model.sql;

/** A named SELECT item or a wildcard, including its source when known. */
public record CompletionProjection(CompletionIdentifier output, CompletionIdentifier qualifier,
                                   CompletionIdentifier column, boolean wildcard) {
}
