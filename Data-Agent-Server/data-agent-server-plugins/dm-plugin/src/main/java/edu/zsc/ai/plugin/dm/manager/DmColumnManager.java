package edu.zsc.ai.plugin.dm.manager;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

import edu.zsc.ai.plugin.capability.ColumnManager;
import edu.zsc.ai.plugin.dm.support.DmMetadataSupport;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;

/**
 * DM column manager. Reuses the SPI default {@code DatabaseMetaData.getColumns}
 * implementation, and supplements empty remarks with comments from
 * ALL_COL_COMMENTS (DM dictionary view).
 */
public final class DmColumnManager implements ColumnManager {

    private final DmMetadataSupport support;

    public DmColumnManager(DmMetadataSupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public List<ColumnMetadata> getColumns(Connection connection, String catalog, String schema, String tableOrViewName) {
        List<ColumnMetadata> columns = ColumnManager.super.getColumns(connection, catalog, schema, tableOrViewName);
        if (columns.isEmpty() || connection == null || StringUtils.isBlank(tableOrViewName)) {
            return columns;
        }

        boolean anyRemarkMissing = columns.stream().anyMatch(c -> StringUtils.isBlank(c.remarks()));
        if (!anyRemarkMissing) {
            return columns;
        }

        String owner = support.resolveSchema(connection, schema);
        String upperTableName = tableOrViewName.trim().toUpperCase(java.util.Locale.ROOT);

        Map<String, String> commentsByColumn = new HashMap<>();
        for (String[] columnComment : support.getColumnComments(connection, owner, upperTableName)) {
            if (StringUtils.isNotBlank(columnComment[0]) && StringUtils.isNotBlank(columnComment[1])) {
                commentsByColumn.put(columnComment[0].toUpperCase(java.util.Locale.ROOT), columnComment[1]);
            }
        }
        if (commentsByColumn.isEmpty()) {
            return columns;
        }

        List<ColumnMetadata> enriched = new ArrayList<>(columns.size());
        for (ColumnMetadata column : columns) {
            if (StringUtils.isNotBlank(column.remarks()) || column.name() == null) {
                enriched.add(column);
                continue;
            }
            String comment = commentsByColumn.get(column.name().toUpperCase(java.util.Locale.ROOT));
            if (comment == null) {
                enriched.add(column);
                continue;
            }
            enriched.add(new ColumnMetadata(
                    column.name(),
                    column.dataType(),
                    column.typeName(),
                    column.columnSize(),
                    column.decimalDigits(),
                    column.nullable(),
                    column.ordinalPosition(),
                    comment,
                    column.isPrimaryKeyPart(),
                    column.isAutoIncrement(),
                    column.isUnsigned(),
                    column.defaultValue()
            ));
        }
        return enriched;
    }
}
