package edu.zsc.ai.plugin.model.sql;

/** Exact metadata spelling; remote synonyms are suggested but never resolved locally. */
public record CompletionSynonym(String owner, String name, String targetSchema, String targetName, String databaseLink,
                                String targetType) {
    public CompletionSynonym {
        databaseLink = java.util.Objects.toString(databaseLink, "");
        targetType = java.util.Objects.toString(targetType, "");
    }
}
