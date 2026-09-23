package edu.zsc.ai.plugin.dm.sql;

import edu.zsc.ai.plugin.capability.SqlSplitter;
import edu.zsc.ai.plugin.sql.DefaultSqlSplitter;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * DM-aware SQL splitter.
 *
 * <p>Plain statements are split on {@code ;} exactly like
 * {@link DefaultSqlSplitter}. PL/SQL-style blocks ({@code CREATE [OR REPLACE]
 * PROCEDURE|FUNCTION|TRIGGER|PACKAGE|TYPE} and anonymous {@code DECLARE/BEGIN}
 * blocks) contain semicolons inside the body, so they must not be split there.
 * Following DM/Oracle client convention (disql / SQL*Plus), such a block is
 * terminated by a line containing only {@code /}; the slash line itself is not
 * part of the statement. If no slash terminator is found, the block runs to
 * end of input as a single statement.
 */
public final class DmSqlSplitter implements SqlSplitter {

    public static final DmSqlSplitter INSTANCE = new DmSqlSplitter();

    private static final int UNDECIDED = 0;
    private static final int PLAIN = 1;
    private static final int PLSQL = 2;

    private DmSqlSplitter() {
    }

    @Override
    public List<String> split(String sql) {
        if (StringUtils.isBlank(sql)) {
            return List.of();
        }

        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        int statementKind = UNDECIDED;
        boolean atLineStart = true;
        boolean slashOnlyLine = true;
        int len = sql.length();

        for (int i = 0; i < len; i++) {
            char c = sql.charAt(i);
            char next = (i + 1 < len) ? sql.charAt(i + 1) : 0;

            if (inLineComment) {
                current.append(c);
                if (c == '\n') {
                    inLineComment = false;
                    atLineStart = true;
                    slashOnlyLine = true;
                }
                continue;
            }

            if (inBlockComment) {
                current.append(c);
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    current.append(next);
                    i++;
                }
                continue;
            }

            if (inSingleQuote) {
                current.append(c);
                if (c == '\'' && next == '\'') {
                    current.append(next);
                    i++;
                } else if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }

            // Slash-alone line terminates a PL/SQL block (not part of the statement).
            if (statementKind == PLSQL && atLineStart && slashOnlyLine && c == '/') {
                addStatement(statements, current);
                statementKind = UNDECIDED;
                while (i + 1 < len && sql.charAt(i + 1) != '\n') {
                    i++;
                }
                atLineStart = true;
                slashOnlyLine = true;
                continue;
            }

            if (c == '\n') {
                current.append(c);
                atLineStart = true;
                slashOnlyLine = true;
                continue;
            }

            if (c == '-' && next == '-') {
                inLineComment = true;
                current.append(c);
                slashOnlyLine = false;
                continue;
            }

            if (c == '/' && next == '*') {
                inBlockComment = true;
                current.append(c);
                slashOnlyLine = false;
                continue;
            }

            if (c == '\'') {
                inSingleQuote = true;
                current.append(c);
                slashOnlyLine = false;
                continue;
            }

            if (!Character.isWhitespace(c)) {
                atLineStart = false;
                if (c != '/') {
                    slashOnlyLine = false;
                }
            }

            if (c == ';' && statementKind != PLSQL) {
                addStatement(statements, current);
                statementKind = UNDECIDED;
                slashOnlyLine = false;
                continue;
            }

            current.append(c);

            if (statementKind == UNDECIDED) {
                statementKind = detectStatementKind(current.toString().stripLeading());
            }
        }

        addStatement(statements, current);
        return statements;
    }

    /**
     * Classify the statement from its leading text. Returns {@link #UNDECIDED}
     * while the head is still an ambiguous prefix (partial keyword, or
     * "CREATE" / "CREATE OR" / "CREATE OR REPLACE" without the object keyword).
     */
    private static int detectStatementKind(String head) {
        List<String> words = leadingWords(head, 4);
        if (words.isEmpty()) {
            return UNDECIDED;
        }
        String first = words.get(0);
        if (!isCompleteWord(head, first) && words.size() == 1) {
            // Partial first word: stay undecided while it may still grow into
            // CREATE / DECLARE / BEGIN; anything else is a plain statement.
            return ("CREATE".startsWith(first) || "DECLARE".startsWith(first) || "BEGIN".startsWith(first))
                    ? UNDECIDED : PLAIN;
        }
        if ("DECLARE".equals(first) || "BEGIN".equals(first)) {
            return PLSQL;
        }
        if (!"CREATE".equals(first)) {
            return PLAIN;
        }
        // CREATE ...: need the object-type keyword, optionally after OR REPLACE.
        int idx = 1;
        if (words.size() <= idx) {
            return UNDECIDED;
        }
        if ("OR".equals(words.get(idx))) {
            idx++;
            if (words.size() <= idx) {
                return UNDECIDED;
            }
            if ("REPLACE".equals(words.get(idx))) {
                idx++;
                if (words.size() <= idx) {
                    return UNDECIDED;
                }
            }
        }
        String keyword = words.get(idx);
        if (!isCompleteWord(head, keyword)) {
            return UNDECIDED;
        }
        return PLSQL_KEYWORDS.contains(keyword) ? PLSQL : PLAIN;
    }

    private static final java.util.Set<String> PLSQL_KEYWORDS =
            java.util.Set.of("PROCEDURE", "FUNCTION", "TRIGGER", "PACKAGE", "TYPE");

    /**
     * Extract up to {@code max} leading whitespace-separated words (uppercased).
     * A trailing partial word (not yet followed by whitespace) is included only
     * when fewer than {@code max} complete words exist, and is marked partial
     * by {@link #isCompleteWord}.
     */
    private static List<String> leadingWords(String head, int max) {
        List<String> words = new ArrayList<>();
        int i = 0;
        int len = head.length();
        while (i < len && words.size() < max) {
            while (i < len && Character.isWhitespace(head.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < len && !Character.isWhitespace(head.charAt(i))) {
                i++;
            }
            if (start < i) {
                words.add(head.substring(start, i).toUpperCase(java.util.Locale.ROOT));
            }
        }
        return words;
    }

    /** True when the last extracted word is followed by a non-word character in head. */
    private static boolean isCompleteWord(String head, String word) {
        String upper = head.toUpperCase(java.util.Locale.ROOT);
        int idx = upper.lastIndexOf(word);
        if (idx < 0) {
            return false;
        }
        int end = idx + word.length();
        return end < head.length() && !isWordChar(head.charAt(end));
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String stmt = current.toString().trim();
        if (stmt.endsWith(";")) {
            stmt = stmt.substring(0, stmt.length() - 1).trim();
        }
        if (!stmt.isEmpty()) {
            statements.add(stmt);
        }
        current.setLength(0);
    }
}
