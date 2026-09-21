package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.capability.IndexManager;
import edu.zsc.ai.plugin.constant.IndexTypeEnum;
import edu.zsc.ai.plugin.dm.constant.DmObjectSql;
import edu.zsc.ai.plugin.dm.support.DmObjectQuerySupport;
import edu.zsc.ai.plugin.model.metadata.IndexMetadata;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * DM index manager backed by ALL_INDEXES / ALL_IND_COLUMNS / ALL_CONSTRAINTS.
 * Overrides the JDBC-metadata default because DM's DatabaseMetaData.getIndexInfo
 * is unreliable; dictionary views also expose the primary-key backing constraint.
 * The catalog parameter is ignored (DM has schemas only).
 */
public final class DmIndexManager implements IndexManager {

    private final DmObjectQuerySupport support;

    public DmIndexManager(DmObjectQuerySupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public List<IndexMetadata> getIndexes(Connection connection, String catalog, String schema, String tableName) {
        if (connection == null || StringUtils.isBlank(tableName)) {
            return List.of();
        }
        String effectiveSchema = support.resolveSchema(connection, schema);
        if (StringUtils.isBlank(effectiveSchema)) {
            return List.of();
        }

        Map<String, IndexRow> indexesByName = new LinkedHashMap<>();
        for (Map<String, Object> row : support.query(
                connection, DmObjectSql.SQL_LIST_INDEXES, effectiveSchema, tableName)) {
            String name = DmObjectQuerySupport.stringValue(row.get("INDEX_NAME"));
            if (StringUtils.isBlank(name)) {
                continue;
            }
            boolean unique = "UNIQUE".equalsIgnoreCase(DmObjectQuerySupport.stringValue(row.get("UNIQUENESS")));
            Object isPrimary = row.get("IS_PRIMARY");
            boolean primaryKey = isPrimary instanceof Number number && number.intValue() == 1;
            indexesByName.put(name, new IndexRow(mapIndexType(
                    DmObjectQuerySupport.stringValue(row.get("INDEX_TYPE"))), unique, primaryKey));
        }
        if (indexesByName.isEmpty()) {
            return List.of();
        }

        Map<String, List<String>> columnsByIndex = new LinkedHashMap<>();
        for (Map<String, Object> row : support.query(
                connection, DmObjectSql.SQL_LIST_INDEX_COLUMNS, effectiveSchema, tableName)) {
            String indexName = DmObjectQuerySupport.stringValue(row.get("INDEX_NAME"));
            String columnName = DmObjectQuerySupport.stringValue(row.get("COLUMN_NAME"));
            if (indexesByName.containsKey(indexName) && StringUtils.isNotBlank(columnName)) {
                columnsByIndex.computeIfAbsent(indexName, ignored -> new ArrayList<>()).add(columnName);
            }
        }

        List<IndexMetadata> indexes = new ArrayList<>();
        for (Map.Entry<String, IndexRow> entry : indexesByName.entrySet()) {
            IndexRow index = entry.getValue();
            indexes.add(new IndexMetadata(
                    entry.getKey(),
                    index.type(),
                    columnsByIndex.getOrDefault(entry.getKey(), List.of()),
                    index.unique(),
                    index.primaryKey()
            ));
        }
        return indexes;
    }

    /** Map DM ALL_INDEXES.INDEX_TYPE to the SPI index type vocabulary. */
    private static String mapIndexType(String dmIndexType) {
        if (dmIndexType == null) {
            return IndexTypeEnum.OTHER.name();
        }
        String type = dmIndexType.toUpperCase(Locale.ROOT);
        if (type.contains("CLUSTER")) {
            return IndexTypeEnum.CLUSTERED.name();
        }
        return IndexTypeEnum.OTHER.name();
    }

    private record IndexRow(String type, boolean unique, boolean primaryKey) {
    }
}
