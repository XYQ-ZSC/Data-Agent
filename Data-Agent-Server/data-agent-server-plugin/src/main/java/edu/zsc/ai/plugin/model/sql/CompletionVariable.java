package edu.zsc.ai.plugin.model.sql;

import java.util.List;

/** A visible procedural binding; rowSource/fields describe record member completion. */
public record CompletionVariable(CompletionIdentifier name, String type, CompletionTableRef rowSource,
                                 List<CompletionVariable> fields, CompletionQuery query) {
    public CompletionVariable(CompletionIdentifier name, String type, CompletionTableRef rowSource, List<CompletionVariable> fields) {
        this(name,type,rowSource,fields,null);
    }
    public CompletionVariable(CompletionIdentifier name, String type) {
        this(name, type, null, List.of());
    }
}
