package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.capability.TriggerManager;
import edu.zsc.ai.plugin.dm.constant.DmObjectSql;
import edu.zsc.ai.plugin.dm.support.DmObjectQuerySupport;
import edu.zsc.ai.plugin.model.metadata.TriggerMetadata;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DM trigger manager backed by ALL_TRIGGERS and DBMS_METADATA.
 * The catalog parameter is ignored (DM has schemas only).
 */
public final class DmTriggerManager implements TriggerManager {

    private final DmObjectQuerySupport support;

    public DmTriggerManager(DmObjectQuerySupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public List<TriggerMetadata> getTriggers(Connection connection, String catalog, String schema, String tableName) {
        if (connection == null) {
            return List.of();
        }
        String effectiveSchema = support.resolveSchema(connection, schema);
        if (StringUtils.isBlank(effectiveSchema)) {
            return List.of();
        }

        boolean hasTableFilter = StringUtils.isNotBlank(tableName);
        StringBuilder sql = new StringBuilder(DmObjectSql.SQL_LIST_TRIGGERS);
        if (hasTableFilter) {
            sql.append(DmObjectSql.SQL_TRIGGER_TABLE_CLAUSE);
        }
        sql.append(DmObjectSql.SQL_TRIGGERS_ORDER_BY);

        List<Map<String, Object>> rows = hasTableFilter
                ? support.query(connection, sql.toString(), effectiveSchema, tableName)
                : support.query(connection, sql.toString(), effectiveSchema);

        List<TriggerMetadata> triggers = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = DmObjectQuerySupport.stringValue(row.get("TRIGGER_NAME"));
            if (StringUtils.isBlank(name)) {
                continue;
            }
            triggers.add(new TriggerMetadata(
                    name,
                    DmObjectQuerySupport.stringValue(row.get("TABLE_NAME")),
                    DmObjectQuerySupport.stringValue(row.get("TRIGGERING_TYPE")),
                    DmObjectQuerySupport.stringValue(row.get("TRIGGERING_EVENT"))
            ));
        }
        return triggers;
    }

    @Override
    public String getTriggerDdl(Connection connection, String catalog, String schema, String triggerName) {
        return support.getObjectDdl(
                connection,
                DmObjectSql.OBJECT_TYPE_TRIGGER,
                support.resolveSchema(connection, schema),
                triggerName
        );
    }

    @Override
    public void deleteTrigger(Connection connection, String catalog, String schema, String triggerName) {
        if (connection == null || StringUtils.isBlank(triggerName)) {
            throw new IllegalArgumentException("Connection and trigger name must not be null or empty");
        }
        String fullName = DmObjectQuerySupport.buildFullIdentifier(
                support.resolveSchema(connection, schema), triggerName);
        support.executeDdl(
                connection,
                String.format(DmObjectSql.SQL_DROP_TRIGGER, fullName),
                "Failed to delete trigger"
        );
    }
}
