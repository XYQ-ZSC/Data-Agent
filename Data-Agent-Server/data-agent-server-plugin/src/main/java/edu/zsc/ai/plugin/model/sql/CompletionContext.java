package edu.zsc.ai.plugin.model.sql;

import java.util.List;
import java.util.Map;

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
 * @param aliases        alias to table reference for the current query block;
 *                       keys are normalized (unquoted names uppercased, quoted
 *                       names keep their exact case)
 * @param tables         tables in the current query block without an alias
 * @param ctes           common table expression names visible at the cursor
 * @param cteColumns     CTE name to its output column names, when derivable
 *                       from the CTE body or its explicit column list
 * @param derivedTables  derived-table alias to its output column names
 * @param replaceStart   UTF-16 offset where the partial word being completed starts
 * @param replaceEnd     UTF-16 offset where the replacement ends (extends past the
 *                       cursor to the end of the word being completed)
 * @param prefix         decoded text before the cursor; does not include the right-hand suffix
 * @param quotedPrefix   whether the replacement token is a quoted identifier
 * @param relations      bindings nearest scope first, preserving self-join relation instances
 * @param commonTables   visible CTE definitions, including explicit names and wildcard projections
 * @param selectList     whether the cursor is at the projection level of a SELECT
 * @param excludedColumns fields already specified in INSERT/UPDATE column contexts
 */
public record CompletionContext(
        CompletionScope scope,
        String qualifier,
        CompletionTableRef qualifiedTable,
        Map<String, CompletionTableRef> aliases,
        List<CompletionTableRef> tables,
        List<String> ctes,
        Map<String, List<String>> cteColumns,
        Map<String, List<String>> derivedTables,
        int replaceStart,
        int replaceEnd,
        String prefix,
        boolean quotedPrefix,
        List<CompletionRelation> relations,
        Map<String, CompletionQuery> commonTables,
        boolean selectList,
        List<String> excludedColumns
) {
}
