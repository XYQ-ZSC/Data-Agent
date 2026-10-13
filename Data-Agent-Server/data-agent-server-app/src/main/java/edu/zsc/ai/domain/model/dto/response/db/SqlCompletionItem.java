package edu.zsc.ai.domain.model.dto.response.db;

/**
 * A single completion candidate.
 *
 * @param label        text shown in the completion popup
 * @param kind         candidate category: KEYWORD, FUNCTION, SCHEMA, TABLE, VIEW, COLUMN, ALIAS
 * @param insertText   text inserted on accept, already escaped per dialect rules
 * @param detail       extra info such as column type or owning table
 * @param replaceStart UTF-16 offset in the request SQL where replacement starts
 * @param replaceEnd   UTF-16 offset in the request SQL where replacement ends
 * @param source       where the candidate came from, e.g. "metadata:HR.EMPLOYEE" or "static"
 */
public record SqlCompletionItem(String label, String kind, String insertText, String detail,
                                int replaceStart, int replaceEnd, String source,
                                String filterText, int rank, String documentation, boolean snippet) {
}
