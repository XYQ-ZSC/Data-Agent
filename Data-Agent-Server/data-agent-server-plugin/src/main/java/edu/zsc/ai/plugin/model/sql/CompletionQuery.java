package edu.zsc.ai.plugin.model.sql;

import java.util.List;

/** A query's output model. Explicit CTE column names override projection names. */
public record CompletionQuery(List<CompletionProjection> projections, List<CompletionRelation> relations,
                              List<CompletionIdentifier> explicitColumns) {
}
