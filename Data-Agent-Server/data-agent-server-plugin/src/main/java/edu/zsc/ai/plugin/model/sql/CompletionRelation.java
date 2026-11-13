package edu.zsc.ai.plugin.model.sql;

/** One FROM occurrence: self-joins keep one instance per alias. */
public record CompletionRelation(CompletionIdentifier qualifier, CompletionTableRef table,
                                 CompletionQuery query, boolean logical, boolean aliased) {
}
