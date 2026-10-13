package edu.zsc.ai.plugin.model.sql;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * Tolerant analysis of the completion point: which candidates are needed and
 * which objects are in scope. Produced by a dialect's completion parser from
 * possibly incomplete SQL; never fails on syntax errors.
 *
 * @param scope          what the cursor position expects
 * @param qualifier      for QUALIFIED: the single qualifier before the dot
 *                       (alias, table or schema name), otherwise null
 * @param qualifiedTable for QUALIFIED with a two-part qualifier (schema.table.):
 *                       the resolved table reference, otherwise null
 * @param replaceStart   UTF-16 offset where the partial word being completed starts
 * @param replaceEnd     UTF-16 offset where the replacement ends (extends past the
 *                       cursor to the end of the word being completed)
 * @param prefix         decoded text before the cursor; does not include the right-hand suffix
 * @param quotedPrefix   whether the replacement token is a quoted identifier
 * @param relations      bindings nearest scope first, preserving self-join relation instances
 * @param commonTables   visible CTE definitions, including explicit names and wildcard projections
 * @param selectList     whether the cursor is at the projection level of a SELECT
 * @param excludedColumns fields already specified in INSERT/UPDATE column contexts
 * @param currentQuery   current output projection; compound ORDER BY uses the first branch
 * @param clause         nearest top-level SQL clause before the replacement token
 * @param statement      current statement's opening keyword
 * @param keywords       syntax-position keyword candidates before prefix filtering
 * @param expressionPosition whether a new expression can start at this point
 * @param variables      nearest procedural declarations, parameters and loop bindings
 * @param procedural     whether the cursor is inside an open procedural block
 */
public record CompletionContext(
        CompletionScope scope,
        String qualifier,
        CompletionTableRef qualifiedTable,
        int replaceStart,
        int replaceEnd,
        String prefix,
        boolean quotedPrefix,
        List<CompletionRelation> relations,
        Map<String, CompletionQuery> commonTables,
        boolean selectList,
        List<String> excludedColumns,
        CompletionQuery currentQuery,
        String clause,
        String statement,
        List<String> keywords,
        boolean expressionPosition,
        List<CompletionVariable> variables,
        boolean procedural
) {
    // Compatibility views are derived from canonical bindings instead of stored twice.
    public Map<String, CompletionTableRef> aliases() {
        Map<String, CompletionTableRef> aliases = new LinkedHashMap<>();
        relations.stream().filter(r -> !r.logical() && r.aliased())
                .forEach(r -> aliases.put(r.qualifier().name(), r.table()));
        return Collections.unmodifiableMap(aliases);
    }

    public List<CompletionTableRef> tables() {
        return relations.stream().filter(r -> !r.logical() && !r.aliased()).map(CompletionRelation::table).toList();
    }

    public List<String> ctes() {
        return List.copyOf(commonTables.keySet());
    }

    public Map<String, List<String>> cteColumns() {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        commonTables.forEach((name, query) -> columns.put(name, names(query)));
        return Collections.unmodifiableMap(columns);
    }

    public Map<String, List<String>> derivedTables() {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        relations.stream().filter(r -> r.logical() && r.aliased())
                .forEach(r -> columns.put(r.qualifier().name(), names(r.query())));
        return Collections.unmodifiableMap(columns);
    }

    private static List<String> names(CompletionQuery query) {
        if (query == null) return List.of();
        if (!query.explicitColumns().isEmpty()) return query.explicitColumns().stream().map(CompletionIdentifier::name).toList();
        return query.projections().stream().filter(p -> p.output() != null).map(p -> p.output().name()).toList();
    }
}
