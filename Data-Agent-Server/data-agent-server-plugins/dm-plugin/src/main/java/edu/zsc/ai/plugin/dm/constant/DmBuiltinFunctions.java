package edu.zsc.ai.plugin.dm.constant;

import edu.zsc.ai.plugin.model.sql.CompletionFunction;
import java.util.*;

/** Offline signatures; online V$IFUN_ARG definitions take precedence over these type families. */
public final class DmBuiltinFunctions {
    private DmBuiltinFunctions() {}
    private static final Map<String, CompletionFunction> DEFINITIONS = definitions();
    public static List<CompletionFunction> templates() {
        return DmKeywords.FUNCTIONS.stream().map(name -> Objects.requireNonNull(DEFINITIONS.get(name), name)).toList();
    }
    private static Map<String, CompletionFunction> definitions() {
        Map<String, CompletionFunction> m = new LinkedHashMap<>();
        group(m, "NUMERIC", "ABS CEIL CEILING FLOOR SQRT EXP LN SIGN", "number:NUMERIC");
        group(m, "NUMERIC", "SUM AVG STDDEV VARIANCE MEDIAN", "value:NUMERIC");
        group(m, "ANY", "MAX MIN", "value:ANY");
        define(m, "COUNT", "BIGINT", "*:ANY");
        group(m, "NUMERIC", "ROUND TRUNC", "number:NUMERIC", "[decimals:INTEGER]");
        define(m, "MOD", "NUMERIC", "number:NUMERIC", "divisor:NUMERIC");
        define(m, "POWER", "NUMERIC", "base:NUMERIC", "exponent:NUMERIC");
        define(m, "LOG", "NUMERIC", "base:NUMERIC", "number:NUMERIC");
        define(m, "RANDOM", "DOUBLE"); define(m, "PI", "DOUBLE");
        group(m, "VARCHAR/CLOB", "UPPER LOWER INITCAP REVERSE", "text:VARCHAR/CLOB");
        group(m, "INTEGER", "LENGTH CHAR_LENGTH ASCII", "text:VARCHAR/CLOB");
        define(m, "CHR", "VARCHAR", "code:INTEGER");
        define(m, "CONCAT", "VARCHAR/CLOB", "text:VARCHAR/CLOB", "text2:VARCHAR/CLOB", "[more:VARCHAR/CLOB...]");
        group(m, "VARCHAR/CLOB", "SUBSTR SUBSTRING", "text:VARCHAR/CLOB", "start:INTEGER", "[length:INTEGER]");
        define(m, "INSTR", "INTEGER", "text:VARCHAR/CLOB", "search:VARCHAR/CLOB", "[start:INTEGER]", "[occurrence:INTEGER]");
        group(m, "VARCHAR/CLOB", "LPAD RPAD", "text:VARCHAR/CLOB", "length:INTEGER", "[padding:VARCHAR]");
        group(m, "VARCHAR/CLOB", "LTRIM RTRIM TRIM", "text:VARCHAR/CLOB", "[characters:VARCHAR]");
        define(m, "REPLACE", "VARCHAR/CLOB", "text:VARCHAR/CLOB", "search:VARCHAR", "[replacement:VARCHAR]");
        define(m, "REPEAT", "VARCHAR/CLOB", "text:VARCHAR/CLOB", "count:INTEGER");
        define(m, "TRANSLATE", "VARCHAR/CLOB", "text:VARCHAR/CLOB", "from:VARCHAR", "to:VARCHAR");
        define(m, "REGEXP_REPLACE", "VARCHAR/CLOB", "text:VARCHAR/CLOB", "pattern:VARCHAR", "[replacement:VARCHAR]", "[position:INTEGER]", "[occurrence:INTEGER]", "[flags:VARCHAR]");
        define(m, "REGEXP_SUBSTR", "VARCHAR/CLOB", "text:VARCHAR/CLOB", "pattern:VARCHAR", "[position:INTEGER]", "[occurrence:INTEGER]", "[flags:VARCHAR]", "[subexpression:INTEGER]");
        define(m, "REGEXP_INSTR", "INTEGER", "text:VARCHAR/CLOB", "pattern:VARCHAR", "[position:INTEGER]", "[occurrence:INTEGER]", "[return_option:INTEGER]", "[flags:VARCHAR]", "[subexpression:INTEGER]");
        define(m, "SPLIT_PART", "VARCHAR", "text:VARCHAR", "delimiter:VARCHAR", "part:INTEGER");
        define(m, "POSITION", "INTEGER", "search:VARCHAR", "text:VARCHAR");
        define(m, "LOCATE", "INTEGER", "search:VARCHAR", "text:VARCHAR", "[start:INTEGER]");
        group(m, "VARCHAR", "LISTAGG GROUP_CONCAT WM_CONCAT", "value:ANY", "[delimiter:VARCHAR]");
        bare(m, "SYSDATE", "TIMESTAMP"); bare(m, "CURRENT_DATE", "DATE");
        bare(m, "CURRENT_TIME", "TIME WITH TIME ZONE"); bare(m, "CURRENT_TIMESTAMP", "TIMESTAMP WITH TIME ZONE");
        bare(m, "LOCALTIMESTAMP", "TIMESTAMP");
        define(m, "NOW", "TIMESTAMP", "[precision:INTEGER]"); define(m, "CURDATE", "DATE");
        define(m, "CURTIME", "TIME WITH TIME ZONE", "[precision:INTEGER]");
        define(m, "TO_DATE", "TIMESTAMP", "text:VARCHAR", "[format:VARCHAR]", "[nls:VARCHAR]");
        define(m, "TO_TIMESTAMP", "TIMESTAMP", "text:VARCHAR", "[format:VARCHAR]", "[nls:VARCHAR]");
        define(m, "TO_CHAR", "VARCHAR", "value:ANY", "[format:VARCHAR]", "[nls:VARCHAR]");
        group(m, "DATETIME", "DATE_ADD DATE_SUB", "date:DATETIME", "interval:INTERVAL");
        define(m, "DATEDIFF", "INTEGER", "unit:DATEPART", "start:DATETIME", "end:DATETIME");
        define(m, "DATEPART", "INTEGER", "unit:DATEPART", "date:DATETIME");
        define(m, "DATE_FORMAT", "VARCHAR", "date:DATETIME", "format:VARCHAR");
        define(m, "EXTRACT", "NUMERIC", "unit:DATEPART", "date:DATETIME");
        group(m, "INTEGER", "YEAR MONTH DAY HOUR MINUTE SECOND WEEK", "date:DATETIME");
        define(m, "ADD_MONTHS", "DATE", "date:DATETIME", "months:INTEGER");
        define(m, "LAST_DAY", "DATE", "date:DATETIME");
        define(m, "MONTHS_BETWEEN", "NUMERIC", "date:DATETIME", "date2:DATETIME");
        define(m, "NEXT_DAY", "DATE", "date:DATETIME", "weekday:VARCHAR");
        define(m, "CAST", "TARGET TYPE", "value:ANY", "type:SQL TYPE");
        define(m, "CONVERT", "TARGET TYPE", "type:SQL TYPE", "value:ANY", "[style:INTEGER]");
        define(m, "TO_NUMBER", "NUMERIC", "value:ANY", "[format:VARCHAR]", "[nls:VARCHAR]");
        define(m, "TO_BLOB", "BLOB", "value:BINARY"); define(m, "TO_CLOB", "CLOB", "value:ANY");
        define(m, "HEXTORAW", "VARBINARY", "text:VARCHAR"); define(m, "RAWTOHEX", "VARCHAR", "value:VARBINARY");
        group(m, "ANY", "NVL IFNULL ISNULL", "value:ANY", "replacement:ANY");
        define(m, "NVL2", "ANY", "value:ANY", "if_not_null:ANY", "if_null:ANY");
        define(m, "COALESCE", "ANY", "value:ANY", "fallback:ANY", "[more:ANY...]");
        define(m, "NULLIF", "ANY", "value:ANY", "other:ANY");
        define(m, "DECODE", "ANY", "value:ANY", "search:ANY", "result:ANY", "[more:ANY...]");
        define(m, "IIF", "ANY", "condition:BOOLEAN", "if_true:ANY", "if_false:ANY");
        group(m, "ANY", "GREATEST LEAST", "value:ANY", "other:ANY", "[more:ANY...]");
        group(m, "BIGINT", "ROW_NUMBER RANK DENSE_RANK");
        group(m, "DOUBLE", "CUME_DIST PERCENT_RANK");
        group(m, "ANY", "LAG LEAD", "value:ANY", "[offset:INTEGER]", "[default:ANY]");
        group(m, "ANY", "FIRST_VALUE LAST_VALUE", "value:ANY");
        define(m, "NTILE", "INTEGER", "buckets:INTEGER");
        bare(m, "CURRENT_USER", "VARCHAR"); bare(m, "SESSION_USER", "VARCHAR");
        group(m, "VARCHAR", "DATABASE UUID NEWID");
        define(m, "IDENT_CURRENT", "NUMERIC", "table:VARCHAR"); define(m, "SCOPE_IDENTITY", "NUMERIC");
        define(m, "ROWIDTOCHAR", "VARCHAR", "rowid:ROWID"); define(m, "TYPEOF", "VARCHAR", "value:ANY");
        m.put("CAST", new CompletionFunction("CAST", "CAST(${1:value} AS ${2:type})", m.get("CAST").signature(), true));
        m.put("EXTRACT", new CompletionFunction("EXTRACT", "EXTRACT(${1:unit} FROM ${2:date})", "EXTRACT(unit:DATEPART FROM date:DATETIME) → NUMERIC", true));
        m.put("POSITION", new CompletionFunction("POSITION", "POSITION(${1:search} IN ${2:text})", "POSITION(search:VARCHAR IN text:VARCHAR) → INTEGER", true));
        return Map.copyOf(m);
    }
    private static void group(Map<String, CompletionFunction> m, String result, String names, String... args) {
        for (String name : names.split(" ")) define(m, name, result, args);
    }
    private static void bare(Map<String, CompletionFunction> m, String name, String result) {
        m.put(name, new CompletionFunction(name, name, name + " → " + result, false));
    }
    private static void define(Map<String, CompletionFunction> m, String name, String result, String... args) {
        List<String> slots = new ArrayList<>();
        for (String arg : args) if (!arg.startsWith("[")) slots.add("${" + (slots.size()+1) + ":" + arg.split(":",2)[0] + "}");
        m.put(name, new CompletionFunction(name, name + "(" + String.join(", ", slots) + ")",
                name + "(" + String.join(", ", args) + ") → " + result, !slots.isEmpty()));
    }
}
