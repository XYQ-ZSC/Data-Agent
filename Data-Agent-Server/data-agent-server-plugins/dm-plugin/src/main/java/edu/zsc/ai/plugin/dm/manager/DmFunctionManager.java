package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.capability.FunctionManager;
import edu.zsc.ai.plugin.dm.constant.DmObjectSql;
import edu.zsc.ai.plugin.dm.support.DmObjectQuerySupport;
import edu.zsc.ai.plugin.model.metadata.FunctionMetadata;
import edu.zsc.ai.plugin.model.metadata.ParameterInfo;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * DM function manager backed by ALL_OBJECTS / ALL_ARGUMENTS and DBMS_METADATA.
 * The catalog parameter is ignored (DM has schemas only).
 */
public final class DmFunctionManager implements FunctionManager {

    private final DmObjectQuerySupport support;

    public DmFunctionManager(DmObjectQuerySupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public List<FunctionMetadata> getFunctions(Connection connection, String catalog, String schema) {
        return searchFunctions(connection, catalog, schema, null);
    }

    @Override
    public List<FunctionMetadata> searchFunctions(Connection connection, String catalog, String schema,
                                                  String functionNamePattern) {
        if (connection == null) {
            return List.of();
        }
        String effectiveSchema = support.resolveSchema(connection, schema);
        if (StringUtils.isBlank(effectiveSchema)) {
            return List.of();
        }

        boolean hasNameFilter = StringUtils.isNotBlank(functionNamePattern) && !"%".equals(functionNamePattern);
        StringBuilder sql = new StringBuilder(DmObjectSql.SQL_LIST_ROUTINES);
        if (hasNameFilter) {
            sql.append(DmObjectSql.SQL_ROUTINES_NAME_CLAUSE);
        }
        sql.append(DmObjectSql.SQL_ROUTINES_ORDER_BY);

        List<Map<String, Object>> rows = hasNameFilter
                ? support.query(connection, sql.toString(), effectiveSchema,
                        DmObjectSql.OBJECT_TYPE_FUNCTION, functionNamePattern)
                : support.query(connection, sql.toString(), effectiveSchema, DmObjectSql.OBJECT_TYPE_FUNCTION);

        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String name = DmObjectQuerySupport.stringValue(row.get("OBJECT_NAME"));
            if (StringUtils.isNotBlank(name)) {
                names.add(name);
            }
        }

        Map<String, List<ParameterInfo>> parametersByName =
                support.fetchParameters(connection, effectiveSchema, names);

        List<FunctionMetadata> functions = new ArrayList<>();
        for (String name : names) {
            List<ParameterInfo> parameters = parametersByName.get(name);
            functions.add(new FunctionMetadata(
                    name,
                    parameters == null || parameters.isEmpty() ? null : parameters,
                    null
            ));
        }
        return functions;
    }

    @Override
    public long countFunctions(Connection connection, String catalog, String schema, String functionNamePattern) {
        if (connection == null) {
            return 0;
        }
        String effectiveSchema = support.resolveSchema(connection, schema);
        if (StringUtils.isBlank(effectiveSchema)) {
            return 0;
        }

        boolean hasNameFilter = StringUtils.isNotBlank(functionNamePattern) && !"%".equals(functionNamePattern);
        String sql = hasNameFilter
                ? DmObjectSql.SQL_COUNT_ROUTINES + DmObjectSql.SQL_ROUTINES_NAME_CLAUSE
                : DmObjectSql.SQL_COUNT_ROUTINES;
        return hasNameFilter
                ? support.count(connection, sql, effectiveSchema, DmObjectSql.OBJECT_TYPE_FUNCTION, functionNamePattern)
                : support.count(connection, sql, effectiveSchema, DmObjectSql.OBJECT_TYPE_FUNCTION);
    }

    @Override
    public String getFunctionDdl(Connection connection, String catalog, String schema, String functionName) {
        return support.getObjectDdl(
                connection,
                DmObjectSql.OBJECT_TYPE_FUNCTION,
                support.resolveSchema(connection, schema),
                functionName
        );
    }

    @Override
    public void deleteFunction(Connection connection, String catalog, String schema, String functionName) {
        if (connection == null || StringUtils.isBlank(functionName)) {
            throw new IllegalArgumentException("Connection and function name must not be null or empty");
        }
        String fullName = DmObjectQuerySupport.buildFullIdentifier(
                support.resolveSchema(connection, schema), functionName);
        support.executeDdl(
                connection,
                String.format(DmObjectSql.SQL_DROP_FUNCTION, fullName),
                "Failed to delete function"
        );
    }
}
