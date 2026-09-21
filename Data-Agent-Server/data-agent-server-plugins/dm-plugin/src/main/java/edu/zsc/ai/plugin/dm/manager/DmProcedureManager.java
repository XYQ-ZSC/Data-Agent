package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.capability.ProcedureManager;
import edu.zsc.ai.plugin.dm.constant.DmObjectSql;
import edu.zsc.ai.plugin.dm.support.DmObjectQuerySupport;
import edu.zsc.ai.plugin.model.metadata.ParameterInfo;
import edu.zsc.ai.plugin.model.metadata.ProcedureMetadata;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * DM procedure manager backed by ALL_OBJECTS / ALL_ARGUMENTS and DBMS_METADATA.
 * The catalog parameter is ignored (DM has schemas only).
 */
public final class DmProcedureManager implements ProcedureManager {

    private final DmObjectQuerySupport support;

    public DmProcedureManager(DmObjectQuerySupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public List<ProcedureMetadata> getProcedures(Connection connection, String catalog, String schema) {
        return searchProcedures(connection, catalog, schema, null);
    }

    @Override
    public List<ProcedureMetadata> searchProcedures(Connection connection, String catalog, String schema,
                                                    String procedureNamePattern) {
        if (connection == null) {
            return List.of();
        }
        String effectiveSchema = support.resolveSchema(connection, schema);
        if (StringUtils.isBlank(effectiveSchema)) {
            return List.of();
        }

        boolean hasNameFilter = StringUtils.isNotBlank(procedureNamePattern) && !"%".equals(procedureNamePattern);
        StringBuilder sql = new StringBuilder(DmObjectSql.SQL_LIST_ROUTINES);
        if (hasNameFilter) {
            sql.append(DmObjectSql.SQL_ROUTINES_NAME_CLAUSE);
        }
        sql.append(DmObjectSql.SQL_ROUTINES_ORDER_BY);

        List<Map<String, Object>> rows = hasNameFilter
                ? support.query(connection, sql.toString(), effectiveSchema,
                        DmObjectSql.OBJECT_TYPE_PROCEDURE, procedureNamePattern)
                : support.query(connection, sql.toString(), effectiveSchema, DmObjectSql.OBJECT_TYPE_PROCEDURE);

        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String name = DmObjectQuerySupport.stringValue(row.get("OBJECT_NAME"));
            if (StringUtils.isNotBlank(name)) {
                names.add(name);
            }
        }

        Map<String, List<ParameterInfo>> parametersByName =
                support.fetchParameters(connection, effectiveSchema, names);

        List<ProcedureMetadata> procedures = new ArrayList<>();
        for (String name : names) {
            List<ParameterInfo> parameters = parametersByName.get(name);
            procedures.add(new ProcedureMetadata(
                    name,
                    parameters == null || parameters.isEmpty() ? null : parameters
            ));
        }
        return procedures;
    }

    @Override
    public long countProcedures(Connection connection, String catalog, String schema, String procedureNamePattern) {
        if (connection == null) {
            return 0;
        }
        String effectiveSchema = support.resolveSchema(connection, schema);
        if (StringUtils.isBlank(effectiveSchema)) {
            return 0;
        }

        boolean hasNameFilter = StringUtils.isNotBlank(procedureNamePattern) && !"%".equals(procedureNamePattern);
        String sql = hasNameFilter
                ? DmObjectSql.SQL_COUNT_ROUTINES + DmObjectSql.SQL_ROUTINES_NAME_CLAUSE
                : DmObjectSql.SQL_COUNT_ROUTINES;
        return hasNameFilter
                ? support.count(connection, sql, effectiveSchema, DmObjectSql.OBJECT_TYPE_PROCEDURE, procedureNamePattern)
                : support.count(connection, sql, effectiveSchema, DmObjectSql.OBJECT_TYPE_PROCEDURE);
    }

    @Override
    public String getProcedureDdl(Connection connection, String catalog, String schema, String procedureName) {
        return support.getObjectDdl(
                connection,
                DmObjectSql.OBJECT_TYPE_PROCEDURE,
                support.resolveSchema(connection, schema),
                procedureName
        );
    }

    @Override
    public void deleteProcedure(Connection connection, String catalog, String schema, String procedureName) {
        if (connection == null || StringUtils.isBlank(procedureName)) {
            throw new IllegalArgumentException("Connection and procedure name must not be null or empty");
        }
        String fullName = DmObjectQuerySupport.buildFullIdentifier(
                support.resolveSchema(connection, schema), procedureName);
        support.executeDdl(
                connection,
                String.format(DmObjectSql.SQL_DROP_PROCEDURE, fullName),
                "Failed to delete procedure"
        );
    }
}
