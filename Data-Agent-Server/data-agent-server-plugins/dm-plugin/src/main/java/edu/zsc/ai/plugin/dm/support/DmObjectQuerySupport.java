package edu.zsc.ai.plugin.dm.support;

import edu.zsc.ai.plugin.dm.constant.DmObjectSql;
import edu.zsc.ai.plugin.model.metadata.ParameterInfo;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plain-JDBC query helper for the DM advanced object layer
 * (index / function / procedure / trigger managers).
 *
 * <p>DM has no catalog: the catalog parameter is ignored by callers and only
 * the schema is used. Unquoted identifiers are stored uppercase in DM, so all
 * dictionary filters compare with {@code UPPER(...) = UPPER(?)}.
 */
public final class DmObjectQuerySupport {

    /**
     * Resolve the effective schema: use the given schema when not blank,
     * otherwise fall back to the connection's current schema (or login user).
     */
    public String resolveSchema(Connection connection, String schema) {
        if (StringUtils.isNotBlank(schema)) {
            return schema.trim();
        }
        if (connection == null) {
            return null;
        }
        try {
            String current = connection.getSchema();
            if (StringUtils.isNotBlank(current)) {
                return current;
            }
        } catch (Throwable ignored) {
            // driver does not support getSchema(); fall through to USER query
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(DmObjectSql.SQL_CURRENT_SCHEMA)) {
            if (rs.next()) {
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve current schema: " + e.getMessage(), e);
        }
        return null;
    }

    /** Quote an identifier with double quotes, doubling embedded quotes. */
    public static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /** Build a full quoted identifier: "SCHEMA"."NAME" (schema part omitted when blank). */
    public static String buildFullIdentifier(String schema, String name) {
        return StringUtils.isNotBlank(schema)
                ? quoteIdentifier(schema) + "." + quoteIdentifier(name)
                : quoteIdentifier(name);
    }

    /** Escape a value for use inside a single-quoted SQL string literal. */
    public static String escapeStringLiteral(String value) {
        return value.replace("'", "''");
    }

    /** Execute a query and return rows as maps keyed by (uppercase) column label. */
    public List<Map<String, Object>> query(Connection connection, String sql, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParams(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                String[] labels = new String[columnCount];
                for (int i = 0; i < columnCount; i++) {
                    labels[i] = meta.getColumnLabel(i + 1);
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < columnCount; i++) {
                        row.put(labels[i], rs.getObject(i + 1));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to execute DM metadata query: " + e.getMessage(), e);
        }
    }

    /** Execute a COUNT(*) query whose first column is aliased TOTAL. */
    public long count(Connection connection, String sql, Object... params) {
        List<Map<String, Object>> rows = query(connection, sql, params);
        if (rows.isEmpty()) {
            return 0;
        }
        Object total = rows.get(0).get("TOTAL");
        return total instanceof Number number ? number.longValue() : 0;
    }

    /**
     * Get object DDL via {@code DBMS_METADATA.GET_DDL(objectType, objectName, schema)}.
     */
    public String getObjectDdl(Connection connection, String objectType, String schema, String objectName) {
        if (connection == null || StringUtils.isBlank(objectName)) {
            return "";
        }
        String sql = String.format(
                DmObjectSql.SQL_GET_OBJECT_DDL,
                escapeStringLiteral(objectType),
                escapeStringLiteral(objectName),
                escapeStringLiteral(StringUtils.defaultString(schema))
        );
        List<Map<String, Object>> rows = query(connection, sql);
        if (rows.isEmpty()) {
            throw new RuntimeException(String.format("Failed to get %s DDL: No result returned", objectType));
        }
        Object ddl = rows.get(0).get("DDL");
        if (ddl == null) {
            throw new RuntimeException(String.format("Failed to get %s DDL for %s", objectType, objectName));
        }
        return ddl.toString();
    }

    /** Execute a DDL/DROP statement, wrapping failures with the given error prefix. */
    public void executeDdl(Connection connection, String sql, String errorMessagePrefix) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException(errorMessagePrefix + ": " + e.getMessage(), e);
        }
    }

    /**
     * Fetch standalone-routine parameters from {@code ALL_ARGUMENTS} for the given object names,
     * grouped by object name and ordered by POSITION.
     */
    public Map<String, List<ParameterInfo>> fetchParameters(Connection connection, String schema, Set<String> objectNames) {
        if (objectNames == null || objectNames.isEmpty()) {
            return Map.of();
        }
        StringBuilder inClause = new StringBuilder();
        for (String name : objectNames) {
            if (!inClause.isEmpty()) {
                inClause.append(',');
            }
            inClause.append('\'').append(escapeStringLiteral(name)).append('\'');
        }
        String sql = String.format(DmObjectSql.SQL_FETCH_ARGUMENTS, inClause);

        Map<String, List<ArgumentRow>> rowsByObject = new LinkedHashMap<>();
        for (Map<String, Object> row : query(connection, sql, schema)) {
            String objectName = stringValue(row.get("OBJECT_NAME"));
            String argumentName = stringValue(row.get("ARGUMENT_NAME"));
            String dataType = stringValue(row.get("DATA_TYPE"));
            Object position = row.get("POSITION");
            int ordinal = position instanceof Number number ? number.intValue() : 0;
            if (StringUtils.isNotBlank(objectName)) {
                rowsByObject.computeIfAbsent(objectName, ignored -> new ArrayList<>())
                        .add(new ArgumentRow(argumentName, dataType, ordinal));
            }
        }

        Map<String, List<ParameterInfo>> parametersByObject = new LinkedHashMap<>();
        for (Map.Entry<String, List<ArgumentRow>> entry : rowsByObject.entrySet()) {
            List<ParameterInfo> parameters = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(ArgumentRow::position))
                    .map(row -> new ParameterInfo(row.name(), row.dataType()))
                    .toList();
            parametersByObject.put(entry.getKey(), parameters);
        }
        return parametersByObject;
    }

    public static String stringValue(Object value) {
        return value != null ? value.toString() : "";
    }

    private static void bindParams(PreparedStatement statement, Object... params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    private record ArgumentRow(String name, String dataType, int position) {
    }
}
