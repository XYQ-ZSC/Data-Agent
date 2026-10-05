package edu.zsc.ai.plugin.dm.constant;

import edu.zsc.ai.plugin.dm.parser.base.DMLexer;
import org.antlr.v4.runtime.Vocabulary;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Static DM (达梦) word lists for SQL completion, following the DM SQL
 * language manual. Keywords are inserted uppercase; functionTemplates supplies
 * parameter snippets and distinguishes bare system expressions.
 */
public final class DmKeywords {

    private DmKeywords() {
    }

    public static List<edu.zsc.ai.plugin.model.sql.CompletionFunction> functionTemplates() {
        Set<String> expressions = Set.of("SYSDATE", "CURRENT_DATE", "CURRENT_TIMESTAMP", "CURRENT_TIME",
                "LOCALTIMESTAMP", "CURRENT_USER", "SESSION_USER");
        Set<String> noArguments = Set.of("PI", "NOW", "CURDATE", "CURTIME", "UUID", "NEWID",
                "DATABASE", "SCOPE_IDENTITY", "ROW_NUMBER", "RANK", "DENSE_RANK", "CUME_DIST", "PERCENT_RANK");
        java.util.Map<String, List<String>> args = java.util.Map.ofEntries(
                java.util.Map.entry("NVL", List.of("value", "replacement")),
                java.util.Map.entry("NVL2", List.of("value", "if_not_null", "if_null")),
                java.util.Map.entry("TO_DATE", List.of("text", "format")),
                java.util.Map.entry("TO_CHAR", List.of("value", "format")),
                java.util.Map.entry("SUBSTR", List.of("text", "start", "length")),
                java.util.Map.entry("ROUND", List.of("number", "decimals")),
                java.util.Map.entry("COUNT", List.of("*")),
                java.util.Map.entry("COALESCE", List.of("value", "fallback")));
        return FUNCTIONS.stream().map(name -> {
            if (expressions.contains(name)) return new edu.zsc.ai.plugin.model.sql.CompletionFunction(name, name, name, false);
            if (noArguments.contains(name)) return new edu.zsc.ai.plugin.model.sql.CompletionFunction(name, name + "()", name + "()", false);
            if (name.equals("CAST")) return new edu.zsc.ai.plugin.model.sql.CompletionFunction(name,
                    "CAST(${1:value} AS ${2:type})", "CAST(value AS type)", true);
            List<String> params = args.getOrDefault(name, List.of(""));
            StringBuilder insert = new StringBuilder(name).append('(');
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) insert.append(", ");
                insert.append("${").append(i+1).append(':').append(params.get(i)).append('}');
            }
            return new edu.zsc.ai.plugin.model.sql.CompletionFunction(name, insert.append(')').toString(),
                    name + "(" + (args.containsKey(name) ? String.join(", ", params) : "…") + ")", true);
        }).toList();
    }

    public static final List<String> KEYWORDS = List.of(
            // Query structure
            "SELECT", "DISTINCT", "ALL", "FROM", "WHERE", "AND", "OR", "NOT",
            "GROUP", "BY", "HAVING", "ORDER", "ASC", "DESC", "LIMIT", "OFFSET",
            "UNION", "INTERSECT", "MINUS", "EXCEPT",
            // Joins
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "OUTER", "ON", "USING", "NATURAL",
            // Predicates
            "IN", "EXISTS", "BETWEEN", "LIKE", "IS", "NULL", "ESCAPE", "REGEXP",
            "CASE", "WHEN", "THEN", "ELSE", "END",
            // Subquery / CTE
            "WITH", "AS", "ANY", "SOME",
            // DML
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "MERGE", "TRUNCATE",
            // DDL
            "CREATE", "ALTER", "DROP", "TABLE", "VIEW", "INDEX", "SEQUENCE", "SYNONYM",
            "SCHEMA", "FUNCTION", "PROCEDURE", "TRIGGER", "PACKAGE", "TYPE", "COMMENT",
            "COLUMN", "CONSTRAINT", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "UNIQUE",
            "CHECK", "DEFAULT", "AUTO_INCREMENT", "IDENTITY",
            // Transaction
            "COMMIT", "ROLLBACK", "SAVEPOINT", "TRANSACTION", "BEGIN", "DECLARE",
            // Permissions
            "GRANT", "REVOKE", "TO", "USER", "ROLE", "PUBLIC",
            // Misc
            "EXPLAIN", "FOR", "FETCH", "FIRST", "NEXT", "ROWS", "ONLY", "ROWNUM",
            "TOP", "PERCENT", "DUAL", "RETURNING", "PARTITION", "OVER", "NULLS", "LAST"
    );

    /**
     * Words that must be double-quoted when used as identifiers. Derived from
     * the DM lexer vocabulary (every token whose literal is a plain word) plus
     * the curated keyword list, so reserved words beyond the curated list —
     * LEVEL, ROWID, SYSDATE and the like — are quoted too. Over-quoting a
     * non-reserved keyword is harmless: the quoted form resolves to the same
     * uppercase name.
     */
    public static final Set<String> RESERVED_WORDS = buildReservedWords();

    /**
     * Reserved words that must be double-quoted when used as identifiers.
     * A pragmatic subset of the keyword list above.
     */
    public static final Set<String> RESERVED = Set.copyOf(KEYWORDS);

    private static Set<String> buildReservedWords() {
        Set<String> words = new HashSet<>(KEYWORDS);
        Vocabulary vocabulary = DMLexer.VOCABULARY;
        for (int type = 1; type <= vocabulary.getMaxTokenType(); type++) {
            String literal = vocabulary.getLiteralName(type);
            if (literal == null || literal.length() < 3 || literal.charAt(0) != '\'') {
                continue;
            }
            String word = literal.substring(1, literal.length() - 1);
            if (word.chars().allMatch(Character::isLetter)) {
                words.add(word.toUpperCase(java.util.Locale.ROOT));
            }
        }
        return Set.copyOf(words);
    }

    public static final List<String> FUNCTIONS = List.of(
            // Aggregate
            "COUNT", "SUM", "AVG", "MAX", "MIN", "STDDEV", "VARIANCE", "MEDIAN",
            "LISTAGG", "GROUP_CONCAT", "WM_CONCAT",
            // Numeric
            "ABS", "CEIL", "CEILING", "FLOOR", "ROUND", "TRUNC", "MOD", "POWER", "SQRT",
            "EXP", "LN", "LOG", "SIGN", "RANDOM", "PI",
            // String
            "LENGTH", "CHAR_LENGTH", "UPPER", "LOWER", "INITCAP", "CONCAT",
            "SUBSTR", "SUBSTRING", "INSTR", "LPAD", "RPAD", "LTRIM", "RTRIM", "TRIM",
            "REPLACE", "REVERSE", "REPEAT", "TRANSLATE", "ASCII", "CHR", "REGEXP_REPLACE",
            "REGEXP_SUBSTR", "REGEXP_INSTR", "SPLIT_PART", "POSITION", "LOCATE",
            // Date / time
            "SYSDATE", "CURRENT_DATE", "CURRENT_TIMESTAMP", "CURRENT_TIME", "LOCALTIMESTAMP",
            "NOW", "CURDATE", "CURTIME", "TO_DATE", "TO_CHAR", "TO_TIMESTAMP",
            "DATE_ADD", "DATE_SUB", "DATEDIFF", "DATEPART", "DATE_FORMAT",
            "EXTRACT", "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND", "WEEK",
            "ADD_MONTHS", "LAST_DAY", "MONTHS_BETWEEN", "NEXT_DAY",
            // Conversion
            "CAST", "CONVERT", "TO_NUMBER", "TO_BLOB", "TO_CLOB", "HEXTORAW", "RAWTOHEX",
            // Null handling / conditional
            "NVL", "NVL2", "COALESCE", "NULLIF", "IFNULL", "ISNULL", "DECODE", "IIF", "GREATEST", "LEAST",
            // Analytic
            "ROW_NUMBER", "RANK", "DENSE_RANK", "LAG", "LEAD", "FIRST_VALUE", "LAST_VALUE",
            "NTILE", "CUME_DIST", "PERCENT_RANK",
            // System / misc
            "CURRENT_USER", "SESSION_USER", "DATABASE", "UUID", "NEWID",
            "IDENT_CURRENT", "SCOPE_IDENTITY", "ROWIDTOCHAR", "TYPEOF"
    );
}
