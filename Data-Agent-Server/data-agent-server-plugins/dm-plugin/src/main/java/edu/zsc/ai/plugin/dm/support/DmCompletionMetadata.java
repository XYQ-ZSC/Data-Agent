package edu.zsc.ai.plugin.dm.support;

import edu.zsc.ai.plugin.dm.constant.DmKeywords;
import edu.zsc.ai.plugin.model.metadata.ParameterInfo;
import edu.zsc.ai.plugin.model.sql.*;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** Read-only, account-visible completion metadata. SQL uses bound exact-case schema/object names. */
@Slf4j
public final class DmCompletionMetadata {
    // https://eco.dameng.com/document/dm/zh-cn/pm/dm8-admin-manual-appendix2.html
    public static final String FUNCTIONS = "SELECT f.ID AS FUNCTION_ID, f.NAME AS FUNCTION_NAME, f.ARG_NUM,"
            + " a.NAME AS ARGUMENT_NAME, a.SEQ, a.DATA_TYPE FROM V$IFUN f"
            + " LEFT JOIN V$IFUN_ARG a ON a.ID = f.ID ORDER BY f.ID, a.SEQ";
    public static final String SETTINGS = "SELECT PARA_NAME, COALESCE(NULLIF(SESS_VALUE, ''), PARA_VALUE) AS PARA_VALUE FROM V$DM_INI"
            + " WHERE PARA_NAME IN ('COMPATIBLE_MODE', 'ORA_DATE_FMT', 'ENABLE_CS_CVT', 'COUNT_64BIT')";
    public static final String PACKAGES = "SELECT OBJECT_NAME FROM ALL_OBJECTS WHERE OWNER = ?"
            + " AND OBJECT_TYPE = 'PACKAGE' ORDER BY OBJECT_NAME";
    public static final String MEMBERS = "SELECT PROCEDURE_NAME, SUBPROGRAM_ID FROM ALL_PROCEDURES WHERE OWNER = ?"
            + " AND OBJECT_NAME = ? AND OBJECT_TYPE = 'PACKAGE' AND PROCEDURE_NAME IS NOT NULL";
    public static final String ARGUMENTS = "SELECT OBJECT_NAME, SUBPROGRAM_ID, DATA_LEVEL, POSITION, ARGUMENT_NAME, DATA_TYPE, IN_OUT"
            + " FROM ALL_ARGUMENTS WHERE OWNER = ? AND PACKAGE_NAME = ?";
    public static final String SYNONYMS = "SELECT s.OWNER, s.SYNONYM_NAME, s.TABLE_OWNER, s.TABLE_NAME, s.DB_LINK,"
            + " o.OBJECT_TYPE AS TARGET_TYPE FROM ALL_SYNONYMS s LEFT JOIN ALL_OBJECTS o"
            + " ON o.OWNER = s.TABLE_OWNER AND o.OBJECT_NAME = s.TABLE_NAME"
            + " WHERE s.OWNER IN (?, 'PUBLIC') ORDER BY s.OWNER, s.SYNONYM_NAME";
    private final DmObjectQuerySupport support;
    public DmCompletionMetadata(DmObjectQuerySupport support) {
        this.support = support;
    }

    public CompletionFunctionCatalog functions(Connection connection) {
        Map<String, String> settings = new LinkedHashMap<>();
        try {
            for (var row : support.query(connection, SETTINGS)) {
                settings.put(text(row, "PARA_NAME"), text(row, "PARA_VALUE"));
            }
        } catch (RuntimeException e) {
            log.debug("DM completion session settings unavailable: {}", e.getMessage());
        }
        String version = null;
        try {
            var metadata = connection.getMetaData();
            if (metadata != null) version = metadata.getDatabaseProductVersion();
        } catch (SQLException | RuntimeException e) {
            log.debug("DM completion database version unavailable: {}", e.getMessage());
        }
        var environment = new CompletionEnvironment(version, Map.copyOf(settings));
        Map<String, CompletionFunction> offline = new LinkedHashMap<>();
        DmKeywords.functionTemplates().forEach(f -> offline.put(f.name(), adapt(f, settings)));
        if ("1".equals(settings.get("ENABLE_CS_CVT"))) {
            offline.put("CONVERT", new CompletionFunction("CONVERT", "CONVERT(${1:text}, ${2:destination_charset})",
                    "CONVERT(text:VARCHAR, destination_charset:VARCHAR, [source_charset:VARCHAR]) → VARCHAR", true));
        }
        try {
            Map<String, Builtin> groups = new LinkedHashMap<>();
            for (var row : support.query(connection, FUNCTIONS)) {
                String name = text(row, "FUNCTION_NAME").toUpperCase(Locale.ROOT);
                if (name.isBlank()) continue;
                Builtin f = groups.computeIfAbsent(text(row, "FUNCTION_ID"), id -> new Builtin(name, integer(row, "ARG_NUM")));
                if (row.get("SEQ") == null) continue;
                int seq = integer(row, "SEQ");
                if (seq == -1) f.result = text(row, "DATA_TYPE");
                else if (seq >= 0) f.args.put(seq, new ParameterInfo(text(row, "ARGUMENT_NAME"), text(row, "DATA_TYPE")));
            }
            if (groups.isEmpty()) return new CompletionFunctionCatalog(List.copyOf(offline.values()), environment, false);
            List<CompletionFunction> functions = new ArrayList<>();
            for (Builtin f : groups.values()) {
                CompletionFunction fallback = offline.get(f.name);
                // SQL grammar functions and bare expressions need their dialect syntax even when registered in V$IFUN.
                if (fallback != null && Set.of("CAST", "EXTRACT", "POSITION", "CONVERT").contains(f.name)) {
                    functions.add(fallback);
                    continue;
                }
                if (fallback != null && !fallback.insertText().contains("(")) {
                    functions.add(new CompletionFunction(f.name, fallback.insertText(),
                            f.result.isBlank() ? fallback.signature() : f.name + " → " + f.result, false));
                    continue;
                }
                List<String> slots = new ArrayList<>(), args = new ArrayList<>();
                for (ParameterInfo arg : f.args.values()) {
                    String name = arg.name().isBlank() ? "arg" + (slots.size() + 1) : arg.name();
                    slots.add("${" + (slots.size() + 1) + ":" + literal(name) + "}");
                    args.add(name + ":" + (arg.dataType().isBlank() ? "ANY" : arg.dataType()));
                }
                if (slots.isEmpty() && f.count != 0) {
                    slots.add("${1:}");
                    args.add("parameters:UNKNOWN");
                }
                String result = f.result.isBlank() ? "UNKNOWN" : f.result;
                functions.add(new CompletionFunction(f.name, f.name + "(" + String.join(", ", slots) + ")",
                        f.name + "(" + String.join(", ", args) + ") → " + result, !slots.isEmpty()));
            }
            for (String grammar : List.of("CAST", "EXTRACT", "POSITION", "CONVERT", "SYSDATE", "CURRENT_DATE",
                    "CURRENT_TIMESTAMP", "CURRENT_TIME", "LOCALTIMESTAMP", "CURRENT_USER", "SESSION_USER")) {
                if (functions.stream().noneMatch(f -> f.name().equals(grammar))) functions.add(offline.get(grammar));
            }
            return new CompletionFunctionCatalog(functions.stream().map(f -> adapt(f, settings)).toList(), environment, true);
        } catch (RuntimeException e) {
            log.debug("DM built-in function metadata unavailable; using offline signatures: {}", e.getMessage());
            return new CompletionFunctionCatalog(List.copyOf(offline.values()), environment, false);
        }
    }
    /** These return types vary with the current session even if the registered system signature is generic. */
    private static CompletionFunction adapt(CompletionFunction function, Map<String, String> settings) {
        String mode = settings.get("COMPATIBLE_MODE"), date = settings.get("ORA_DATE_FMT"), result = null;
        if (function.name().equals("TO_DATE") && mode != null)
            result = mode.equals("2") && "1".equals(date) ? "DATE" : "TIMESTAMP";
        if (function.name().equals("CURRENT_DATE") && mode != null)
            result = Set.of("0", "4").contains(mode) || mode.equals("2") && "1".equals(date) ? "DATE" : "TIMESTAMP";
        if (function.name().equals("COUNT") && settings.containsKey("COUNT_64BIT"))
            result = "1".equals(settings.get("COUNT_64BIT")) ? "BIGINT" : "INTEGER";
        if (result == null) return function;
        int arrow = function.signature().lastIndexOf(" → ");
        return new CompletionFunction(function.name(), function.insertText(),
                (arrow < 0 ? function.signature() : function.signature().substring(0, arrow)) + " → " + result, function.snippet());
    }
    private static final class Builtin {
        final String name;
        final int count;
        String result = "";
        final SortedMap<Integer, ParameterInfo> args = new TreeMap<>();
        Builtin(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }
    public List<String> packages(Connection connection, String schema) {
        String owner = support.resolveSchema(connection, schema);
        return support.query(connection, PACKAGES, owner).stream().map(r -> text(r, "OBJECT_NAME"))
                .filter(n -> !n.isBlank()).distinct().toList();
    }
    public List<CompletionRoutine> members(Connection connection, String schema, String packageName) {
        String owner = support.resolveSchema(connection, schema);
        List<Map<String, Object>> members = support.query(connection, MEMBERS, owner, packageName);
        List<Map<String, Object>> arguments = support.query(connection, ARGUMENTS, owner, packageName);
        List<CompletionRoutine> result = new ArrayList<>();
        for (var member : members) {
            String name = text(member, "PROCEDURE_NAME"), id = text(member, "SUBPROGRAM_ID");
            List<Map<String, Object>> rows = arguments.stream().filter(a -> text(a, "OBJECT_NAME").equals(name)
                    && text(a, "SUBPROGRAM_ID").equals(id) && integer(a, "DATA_LEVEL") == 0)
                    .sorted(Comparator.comparingInt(a -> integer(a, "POSITION"))).toList();
            String returnType = rows.stream().filter(a -> integer(a, "POSITION") == 0)
                    .map(a -> text(a, "DATA_TYPE")).findFirst().orElse(null);
            List<ParameterInfo> parameters = rows.stream().filter(a -> integer(a, "POSITION") > 0)
                    .map(a -> new ParameterInfo(text(a, "ARGUMENT_NAME"), text(a, "DATA_TYPE")
                            + (text(a, "IN_OUT").isBlank() ? "" : " " + text(a, "IN_OUT")))).toList();
            result.add(new CompletionRoutine(name, parameters, returnType, returnType == null, id));
        }
        return List.copyOf(result);
    }
    public List<CompletionSynonym> synonyms(Connection connection, String schema) {
        String owner = support.resolveSchema(connection, schema);
        return support.query(connection, SYNONYMS, owner).stream().map(r -> new CompletionSynonym(text(r, "OWNER"),
                text(r, "SYNONYM_NAME"), text(r, "TABLE_OWNER"), text(r, "TABLE_NAME"), text(r, "DB_LINK"), text(r, "TARGET_TYPE")))
                .distinct().toList();
    }
    private static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }

    private static int integer(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(Objects.toString(value, "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String literal(String name) {
        return name.replace("\\", "\\\\").replace("$", "\\$").replace("}", "\\}");
    }
}
