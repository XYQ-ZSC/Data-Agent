package edu.zsc.ai.plugin.sql;

import edu.zsc.ai.plugin.capability.SqlValidator;
import edu.zsc.ai.plugin.model.sql.SqlError;
import edu.zsc.ai.plugin.model.sql.SqlType;
import edu.zsc.ai.plugin.model.sql.SqlValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Default fallback SqlValidator when a plugin does not provide its own implementation.
 * Uses keyword-based classification without full syntax validation.
 */
public class DefaultSqlValidator implements SqlValidator {

    public static final DefaultSqlValidator INSTANCE = new DefaultSqlValidator();

    @Override
    public SqlValidationResult validate(String sql) {
        SqlType type = classifySql(sql);
        if (sql == null || sql.isBlank()) {
            return invalid(type, "SQL statement is empty");
        }
        if (type == SqlType.UNKNOWN) {
            return invalid(type, "Unrecognized SQL statement");
        }

        String structureError = findStructureError(sql);
        if (structureError != null) {
            return invalid(type, structureError);
        }

        if (type == SqlType.SELECT) {
            return validateSelect(stripLeadingComments(sql).stripLeading());
        }
        return SqlValidationResult.valid(type, List.of(), List.of());
    }

    private SqlValidationResult validateSelect(String sql) {
        if (!startsWithKeyword(sql, "SELECT")) {
            return SqlValidationResult.valid(SqlType.SELECT, List.of(), List.of());
        }

        int selectEnd = "SELECT".length();
        int fromIndex = findTopLevelKeyword(sql, "FROM", selectEnd);
        String projection = (fromIndex < 0 ? sql.substring(selectEnd) : sql.substring(selectEnd, fromIndex)).trim();
        projection = stripTrailingSemicolon(projection);
        if (projection.isEmpty()) {
            return invalid(SqlType.SELECT, "SELECT list must not be empty");
        }

        List<String> columns = splitTopLevel(projection, ',');
        List<String> tables = List.of();
        if (fromIndex >= 0) {
            String fromClause = stripTrailingSemicolon(sql.substring(fromIndex + "FROM".length()).trim());
            if (fromClause.isEmpty()) {
                return invalid(SqlType.SELECT, "FROM clause must contain a table");
            }
            String table = firstToken(fromClause);
            if (table.isEmpty()) {
                return invalid(SqlType.SELECT, "FROM clause must contain a table");
            }
            tables = List.of(table);
        }
        return SqlValidationResult.valid(SqlType.SELECT, tables, columns);
    }

    private SqlValidationResult invalid(SqlType type, String message) {
        return SqlValidationResult.invalid(type, List.of(new SqlError(1, 0, message)));
    }

    @Override
    public SqlType classifySql(String sql) {
        if (sql == null || sql.isBlank()) {
            return SqlType.UNKNOWN;
        }
        String stripped = stripLeadingComments(sql).stripLeading();
        if (stripped.isEmpty()) {
            return SqlType.UNKNOWN;
        }
        String firstWord = stripped.split("\\s+")[0].replaceFirst(";$", "").toUpperCase(Locale.ROOT);
        return switch (firstWord) {
            case "SELECT", "WITH" -> SqlType.SELECT;
            case "INSERT" -> SqlType.INSERT;
            case "UPDATE" -> SqlType.UPDATE;
            case "DELETE" -> SqlType.DELETE;
            case "MERGE" -> SqlType.MERGE;
            case "CREATE" -> SqlType.CREATE;
            case "ALTER" -> SqlType.ALTER;
            case "DROP" -> SqlType.DROP;
            case "TRUNCATE" -> SqlType.TRUNCATE;
            case "GRANT" -> SqlType.GRANT;
            case "REVOKE" -> SqlType.REVOKE;
            case "SHOW" -> SqlType.SHOW;
            case "EXPLAIN" -> SqlType.EXPLAIN;
            case "DESCRIBE", "DESC" -> SqlType.DESCRIBE;
            case "USE" -> SqlType.USE;
            case "SET" -> SqlType.SET;
            case "BEGIN", "START" -> SqlType.BEGIN;
            case "COMMIT" -> SqlType.COMMIT;
            case "ROLLBACK" -> SqlType.ROLLBACK;
            default -> SqlType.UNKNOWN;
        };
    }

    private String stripLeadingComments(String sql) {
        String s = sql.stripLeading();
        while (!s.isEmpty()) {
            if (s.startsWith("--")) {
                int nl = s.indexOf('\n');
                s = (nl == -1) ? "" : s.substring(nl + 1).stripLeading();
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                s = (end == -1) ? "" : s.substring(end + 2).stripLeading();
            } else {
                break;
            }
        }
        return s;
    }

    private String findStructureError(String sql) {
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        int parentheses = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (lineComment) {
                if (c == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (singleQuote) {
                if (c == '\'' && next == '\'') i++;
                else if (c == '\'') singleQuote = false;
                continue;
            }
            if (doubleQuote) {
                if (c == '"' && next == '"') i++;
                else if (c == '"') doubleQuote = false;
                continue;
            }
            if (c == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (c == '\'') {
                singleQuote = true;
            } else if (c == '"') {
                doubleQuote = true;
            } else if (c == '(') {
                parentheses++;
            } else if (c == ')' && --parentheses < 0) {
                return "Unexpected closing parenthesis";
            }
        }
        if (singleQuote) return "Unclosed string literal";
        if (doubleQuote) return "Unclosed quoted identifier";
        if (blockComment) return "Unclosed block comment";
        if (parentheses != 0) return "Unclosed parenthesis";
        return null;
    }

    private int findTopLevelKeyword(String sql, String keyword, int start) {
        boolean singleQuote = false;
        boolean doubleQuote = false;
        int parentheses = 0;
        for (int i = start; i <= sql.length() - keyword.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (singleQuote) {
                if (c == '\'' && next == '\'') i++;
                else if (c == '\'') singleQuote = false;
            } else if (doubleQuote) {
                if (c == '"' && next == '"') i++;
                else if (c == '"') doubleQuote = false;
            } else if (c == '\'') {
                singleQuote = true;
            } else if (c == '"') {
                doubleQuote = true;
            } else if (c == '(') {
                parentheses++;
            } else if (c == ')') {
                parentheses--;
            } else if (parentheses == 0
                    && (i == 0 || !isIdentifierChar(sql.charAt(i - 1)))
                    && startsWithKeyword(sql.substring(i), keyword)) {
                return i;
            }
        }
        return -1;
    }

    private boolean startsWithKeyword(String sql, String keyword) {
        if (sql.length() < keyword.length() || !sql.regionMatches(true, 0, keyword, 0, keyword.length())) {
            return false;
        }
        return sql.length() == keyword.length() || !isIdentifierChar(sql.charAt(keyword.length()));
    }

    private boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    private List<String> splitTopLevel(String value, char delimiter) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuote = false;
        boolean doubleQuote = false;
        int parentheses = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            char next = i + 1 < value.length() ? value.charAt(i + 1) : 0;
            if (singleQuote && c == '\'' && next == '\'') {
                current.append(c).append(next);
                i++;
                continue;
            }
            if (doubleQuote && c == '"' && next == '"') {
                current.append(c).append(next);
                i++;
                continue;
            }
            if (!doubleQuote && c == '\'') singleQuote = !singleQuote;
            else if (!singleQuote && c == '"') doubleQuote = !doubleQuote;
            else if (!singleQuote && !doubleQuote && c == '(') parentheses++;
            else if (!singleQuote && !doubleQuote && c == ')') parentheses--;

            if (!singleQuote && !doubleQuote && parentheses == 0 && c == delimiter) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString().trim());
        return values;
    }

    private String firstToken(String value) {
        boolean doubleQuote = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            char next = i + 1 < value.length() ? value.charAt(i + 1) : 0;
            if (doubleQuote && c == '"' && next == '"') {
                i++;
            } else if (c == '"') {
                doubleQuote = !doubleQuote;
            } else if (!doubleQuote && Character.isWhitespace(c)) {
                return value.substring(0, i);
            }
        }
        return value;
    }

    private String stripTrailingSemicolon(String value) {
        String stripped = value.stripTrailing();
        return stripped.endsWith(";") ? stripped.substring(0, stripped.length() - 1).stripTrailing() : stripped;
    }
}
