package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.plugin.model.metadata.ConstraintMetadata;
import edu.zsc.ai.plugin.model.metadata.IndexMetadata;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns foreign-key rows into diagram relations. Rows that share a foreign-key
 * name become one relation, ordered by key sequence. The referenced end is one.
 * The foreign-key end is one when those columns contain a unique key, and many
 * otherwise. A local table with exactly two disjoint foreign keys, whose columns
 * together equal its primary key or a unique key, also contributes one
 * many-to-many relation between the referenced tables. Tables outside the opened
 * schema stay in the graph as external endpoints, capped so one hub cannot
 * pull in every referenced schema.
 */
final class ErDiagramGraph {

    static final int EXTERNAL_TABLE_LIMIT = 40;

    private ErDiagramGraph() {
    }

    enum Cardinality {
        ONE_TO_ONE,
        ONE_TO_MANY,
        MANY_TO_MANY
    }

    record Scope(String catalog, String schema, boolean schemaScoped) {
    }

    record KeyRow(
            String pkCatalog,
            String pkSchema,
            String pkTable,
            String pkColumn,
            String fkCatalog,
            String fkSchema,
            String fkTable,
            String fkColumn,
            String name,
            int keySeq) {

        KeyRow(String pkCatalog, String pkSchema, String pkTable, String pkColumn,
               String fkCatalog, String fkSchema, String fkTable, String fkColumn, String name) {
            this(pkCatalog, pkSchema, pkTable, pkColumn, fkCatalog, fkSchema, fkTable, fkColumn, name, 0);
        }
    }

    record Endpoint(String catalog, String schema, String table, boolean external) {
    }

    record TableRef(String catalog, String schema, String table) {
    }

    /** Columns of one primary key or unique index on a table. */
    record UniqueKey(String catalog, String schema, String table, List<String> columns) {
    }

    record Relation(String name, Endpoint from, List<String> fromColumns, Endpoint to, List<String> toColumns,
                    Cardinality cardinality, Endpoint via) {

        Relation(String name, Endpoint from, List<String> fromColumns, Endpoint to, List<String> toColumns,
                 Cardinality cardinality) {
            this(name, from, fromColumns, to, toColumns, cardinality, null);
        }

        String fromColumn() {
            return first(fromColumns);
        }

        String toColumn() {
            return first(toColumns);
        }

        private static String first(List<String> columns) {
            return columns == null || columns.isEmpty() ? "" : columns.get(0);
        }
    }

    record AssembleResult(List<Relation> relations, List<Endpoint> externalTables, boolean externalTruncated) {
    }

    record StubColumn(String name, boolean primaryKey) {
    }

    /**
     * Outbound foreign keys from a dictionary that names the referenced schema.
     * Same-schema constraints are left to JDBC so a second name cannot duplicate them.
     */
    static List<KeyRow> keysFromConstraints(String catalog, String schema, String tableName,
                                            boolean schemaScoped, List<ConstraintMetadata> constraints) {
        if (StringUtils.isBlank(tableName) || constraints == null) {
            return List.of();
        }
        List<KeyRow> keys = new ArrayList<>();
        for (ConstraintMetadata constraint : constraints) {
            if (!isForeignKey(constraint) || !isCrossNamespace(schemaScoped, catalog, schema, constraint.referencedSchema())) {
                continue;
            }
            List<String> fkColumns = constraint.columns() == null ? List.of() : constraint.columns();
            List<String> pkColumns = constraint.referencedColumns() == null ? List.of() : constraint.referencedColumns();
            int pairs = Math.min(fkColumns.size(), pkColumns.size());
            for (int i = 0; i < pairs; i++) {
                if (StringUtils.isAnyBlank(fkColumns.get(i), pkColumns.get(i), constraint.referencedTable())) {
                    continue;
                }
                String referencedNamespace = constraint.referencedSchema();
                keys.add(new KeyRow(
                        schemaScoped ? null : referencedNamespace,
                        schemaScoped ? referencedNamespace : null,
                        constraint.referencedTable(),
                        pkColumns.get(i),
                        catalog,
                        schema,
                        tableName,
                        fkColumns.get(i),
                        constraint.name(),
                        i + 1));
            }
        }
        return keys;
    }

    static List<UniqueKey> uniqueKeysFromIndexes(String catalog, String schema, String table, List<IndexMetadata> indexes) {
        if (StringUtils.isBlank(table) || indexes == null) {
            return List.of();
        }
        List<UniqueKey> keys = new ArrayList<>();
        for (IndexMetadata index : indexes) {
            if (index == null || (!index.unique() && !index.isPrimaryKey()) || index.columns() == null) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            for (String column : index.columns()) {
                if (StringUtils.isNotBlank(column)) {
                    columns.add(column);
                }
            }
            if (!columns.isEmpty()) {
                keys.add(new UniqueKey(catalog, schema, table, List.copyOf(columns)));
            }
        }
        return keys;
    }

    /**
     * Tables that own a foreign key in this diagram. Local tables use the opened
     * catalog and schema. External children keep the schema named by the key.
     */
    static List<TableRef> foreignKeyTables(Scope scope, Map<String, String> localNames, List<KeyRow> keys) {
        Map<String, TableRef> tables = new LinkedHashMap<>();
        if (keys == null) {
            return List.of();
        }
        for (KeyRow key : keys) {
            if (key == null || StringUtils.isBlank(key.fkTable())) {
                continue;
            }
            TableRef table = tableRef(scope, localNames, key.fkCatalog(), key.fkSchema(), key.fkTable());
            if (table != null) {
                tables.putIfAbsent(tableId(scope, table), table);
            }
        }
        return new ArrayList<>(tables.values());
    }

    static List<StubColumn> stubColumns(Endpoint table, List<Relation> relations) {
        Map<String, Boolean> primaryKeyByName = new LinkedHashMap<>();
        if (relations != null && table != null) {
            for (Relation relation : relations) {
                if (relation.cardinality() != Cardinality.MANY_TO_MANY && table.equals(relation.to())) {
                    markColumns(primaryKeyByName, relation.toColumns(), true);
                }
            }
            for (Relation relation : relations) {
                if (relation.cardinality() != Cardinality.MANY_TO_MANY && table.equals(relation.from())) {
                    markColumns(primaryKeyByName, relation.fromColumns(), false);
                }
            }
        }
        List<StubColumn> columns = new ArrayList<>();
        primaryKeyByName.forEach((name, primaryKey) -> columns.add(new StubColumn(name, primaryKey)));
        return columns;
    }

    static AssembleResult assemble(Scope scope, Map<String, String> localNames, List<KeyRow> keys) {
        return assemble(scope, localNames, keys, List.of());
    }

    static AssembleResult assemble(Scope scope, Map<String, String> localNames, List<KeyRow> keys, List<UniqueKey> uniqueKeys) {
        Map<String, Endpoint> externalById = new LinkedHashMap<>();
        Map<String, ConstraintGroup> groups = new LinkedHashMap<>();
        if (keys != null) {
            for (KeyRow key : keys) {
                if (key == null || StringUtils.isAnyBlank(key.pkColumn(), key.fkColumn())) {
                    continue;
                }
                Endpoint pk = resolve(scope, localNames, externalById, key.pkCatalog(), key.pkSchema(), key.pkTable());
                Endpoint fk = resolve(scope, localNames, externalById, key.fkCatalog(), key.fkSchema(), key.fkTable());
                if (pk == null || fk == null) {
                    continue;
                }
                String groupId = groupId(scope, fk, pk, key);
                groups.computeIfAbsent(groupId, id -> new ConstraintGroup(StringUtils.defaultString(key.name()), fk, pk))
                        .addPair(key.fkColumn(), key.pkColumn(), key.keySeq());
            }
        }

        List<Endpoint> externalTables = new ArrayList<>(externalById.values());
        boolean externalTruncated = externalTables.size() > EXTERNAL_TABLE_LIMIT;
        if (externalTruncated) {
            externalTables = new ArrayList<>(externalTables.subList(0, EXTERNAL_TABLE_LIMIT));
        }
        Map<String, Endpoint> keptExternal = new LinkedHashMap<>();
        for (Endpoint endpoint : externalTables) {
            keptExternal.put(endpointId(scope, endpoint), endpoint);
        }

        Map<String, List<List<String>>> uniqueByTable = indexUniqueKeys(scope, localNames, externalById, uniqueKeys);
        List<Relation> relations = new ArrayList<>();
        for (ConstraintGroup group : groups.values()) {
            if (missingExternal(scope, keptExternal, group.from) || missingExternal(scope, keptExternal, group.to)) {
                continue;
            }
            List<String> fromColumns = group.fromColumns();
            List<String> toColumns = group.toColumns();
            relations.add(new Relation(
                    group.name,
                    group.from,
                    fromColumns,
                    group.to,
                    toColumns,
                    cardinalityOf(fromColumns, uniqueByTable.get(endpointId(scope, group.from)))));
        }
        relations.addAll(manyToMany(scope, relations, uniqueByTable));
        return new AssembleResult(relations, externalTables, externalTruncated);
    }

    /**
     * Junction tables are local tables only. An external table is not expanded,
     * so one of its foreign keys is not evidence of a many-to-many link.
     */
    private static List<Relation> manyToMany(Scope scope, List<Relation> relations, Map<String, List<List<String>>> uniqueByTable) {
        Map<String, List<Relation>> foreignKeysByTable = new LinkedHashMap<>();
        for (Relation relation : relations) {
            if (relation.from().external() || relation.cardinality() == Cardinality.MANY_TO_MANY) {
                continue;
            }
            foreignKeysByTable.computeIfAbsent(endpointId(scope, relation.from()), id -> new ArrayList<>()).add(relation);
        }
        List<Relation> derived = new ArrayList<>();
        for (List<Relation> foreignKeys : foreignKeysByTable.values()) {
            if (foreignKeys.size() != 2) {
                continue;
            }
            Relation left = foreignKeys.get(0);
            Relation right = foreignKeys.get(1);
            if (!disjoint(left.fromColumns(), right.fromColumns())) {
                continue;
            }
            List<String> union = new ArrayList<>(left.fromColumns().size() + right.fromColumns().size());
            union.addAll(left.fromColumns());
            union.addAll(right.fromColumns());
            if (!sameColumnSet(union, uniqueByTable.get(endpointId(scope, left.from())))) {
                continue;
            }
            derived.add(manyToMany(scope, left, right));
        }
        return derived;
    }

    private static Relation manyToMany(Scope scope, Relation left, Relation right) {
        Relation first = compareParents(scope, left, right) <= 0 ? left : right;
        Relation second = first == left ? right : left;
        return new Relation(
                first.from().table(),
                first.to(),
                List.copyOf(first.toColumns()),
                second.to(),
                List.copyOf(second.toColumns()),
                Cardinality.MANY_TO_MANY,
                first.from());
    }

    private static int compareParents(Scope scope, Relation left, Relation right) {
        int compared = endpointId(scope, left.to()).compareTo(endpointId(scope, right.to()));
        if (compared != 0) {
            return compared;
        }
        compared = columnKey(left.toColumns()).compareTo(columnKey(right.toColumns()));
        if (compared != 0) {
            return compared;
        }
        return columnKey(left.fromColumns()).compareTo(columnKey(right.fromColumns()));
    }

    private static boolean disjoint(List<String> left, List<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return false;
        }
        Set<String> names = new HashSet<>();
        for (String column : left) {
            if (StringUtils.isNotBlank(column)) {
                names.add(norm(column));
            }
        }
        for (String column : right) {
            if (StringUtils.isNotBlank(column) && names.contains(norm(column))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameColumnSet(List<String> columns, List<List<String>> uniqueSets) {
        if (columns == null || columns.isEmpty() || uniqueSets == null) {
            return false;
        }
        for (List<String> unique : uniqueSets) {
            if (sameColumns(columns, unique)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameColumns(List<String> left, List<String> right) {
        Set<String> leftNames = columnSet(left);
        return !leftNames.isEmpty() && leftNames.equals(columnSet(right));
    }

    private static Set<String> columnSet(List<String> columns) {
        Set<String> names = new HashSet<>();
        if (columns != null) {
            for (String column : columns) {
                if (StringUtils.isNotBlank(column)) {
                    names.add(norm(column));
                }
            }
        }
        return names;
    }

    private static String columnKey(List<String> columns) {
        List<String> names = new ArrayList<>();
        if (columns != null) {
            for (String column : columns) {
                if (StringUtils.isNotBlank(column)) {
                    names.add(norm(column));
                }
            }
        }
        names.sort(Comparator.naturalOrder());
        return String.join("\0", names);
    }

    private static void markColumns(Map<String, Boolean> primaryKeyByName, List<String> columns, boolean primaryKey) {
        if (columns == null) {
            return;
        }
        for (String column : columns) {
            if (StringUtils.isNotBlank(column)) {
                primaryKeyByName.putIfAbsent(column, primaryKey);
            }
        }
    }

    private static Cardinality cardinalityOf(List<String> foreignKeyColumns, List<List<String>> uniqueSets) {
        if (uniqueSets == null || uniqueSets.isEmpty() || foreignKeyColumns == null || foreignKeyColumns.isEmpty()) {
            return Cardinality.ONE_TO_MANY;
        }
        Set<String> foreignKey = new HashSet<>();
        for (String column : foreignKeyColumns) {
            if (StringUtils.isNotBlank(column)) {
                foreignKey.add(norm(column));
            }
        }
        for (List<String> unique : uniqueSets) {
            if (covers(foreignKey, unique)) {
                return Cardinality.ONE_TO_ONE;
            }
        }
        return Cardinality.ONE_TO_MANY;
    }

    /** A unique key makes the foreign key unique when every unique column is one of the foreign-key columns. */
    private static boolean covers(Set<String> foreignKey, List<String> unique) {
        if (unique == null || unique.isEmpty()) {
            return false;
        }
        for (String column : unique) {
            if (StringUtils.isBlank(column) || !foreignKey.contains(norm(column))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, List<List<String>>> indexUniqueKeys(
            Scope scope, Map<String, String> localNames, Map<String, Endpoint> externalById, List<UniqueKey> uniqueKeys) {
        Map<String, List<List<String>>> byTable = new LinkedHashMap<>();
        if (uniqueKeys == null) {
            return byTable;
        }
        for (UniqueKey uniqueKey : uniqueKeys) {
            if (uniqueKey == null) {
                continue;
            }
            Endpoint table = matchTable(scope, localNames, externalById, uniqueKey.catalog(), uniqueKey.schema(), uniqueKey.table());
            if (table == null || uniqueKey.columns() == null) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            for (String column : uniqueKey.columns()) {
                if (StringUtils.isNotBlank(column)) {
                    columns.add(column);
                }
            }
            if (!columns.isEmpty()) {
                byTable.computeIfAbsent(endpointId(scope, table), id -> new ArrayList<>()).add(columns);
            }
        }
        return byTable;
    }

    private static Endpoint matchTable(
            Scope scope, Map<String, String> localNames, Map<String, Endpoint> externalById,
            String catalog, String schema, String table) {
        if (StringUtils.isBlank(table)) {
            return null;
        }
        if (sameNamespace(scope, catalog, schema)) {
            if (localNames == null) {
                return null;
            }
            String canonical = localNames.get(norm(table));
            if (canonical == null) {
                return null;
            }
            return new Endpoint(scope.catalog(), scope.schema(), canonical, false);
        }
        return externalById.get(externalIdentity(scope, catalog, schema, table));
    }

    private static String groupId(Scope scope, Endpoint from, Endpoint to, KeyRow key) {
        if (StringUtils.isBlank(key.name())) {
            return endpointId(scope, from) + '\0' + norm(key.fkColumn())
                    + '\0' + endpointId(scope, to) + '\0' + norm(key.pkColumn());
        }
        return endpointId(scope, from) + '\0' + norm(key.name()) + '\0' + endpointId(scope, to);
    }

    private static boolean missingExternal(Scope scope, Map<String, Endpoint> keptExternal, Endpoint endpoint) {
        return endpoint.external() && !keptExternal.containsKey(endpointId(scope, endpoint));
    }

    private static Endpoint resolve(
            Scope scope,
            Map<String, String> localNames,
            Map<String, Endpoint> externalById,
            String catalog,
            String schema,
            String table) {
        if (StringUtils.isBlank(table)) {
            return null;
        }
        if (sameNamespace(scope, catalog, schema)) {
            if (localNames == null) {
                return null;
            }
            String canonical = localNames.get(norm(table));
            if (canonical == null) {
                return null;
            }
            return new Endpoint(scope.catalog(), scope.schema(), canonical, false);
        }
        String identity = externalIdentity(scope, catalog, schema, table);
        Endpoint existing = externalById.get(identity);
        if (existing != null) {
            return existing;
        }
        Endpoint created = externalEndpoint(scope, catalog, schema, table.trim());
        externalById.put(identity, created);
        return created;
    }

    private static boolean sameNamespace(Scope scope, String catalog, String schema) {
        if (scope.schemaScoped()) {
            if (StringUtils.isBlank(schema) || StringUtils.isBlank(scope.schema())) {
                return true;
            }
            return schema.trim().equalsIgnoreCase(scope.schema().trim());
        }
        if (StringUtils.isBlank(catalog) || StringUtils.isBlank(scope.catalog())) {
            return true;
        }
        return catalog.trim().equalsIgnoreCase(scope.catalog().trim());
    }

    private static Endpoint externalEndpoint(Scope scope, String catalog, String schema, String table) {
        if (scope.schemaScoped()) {
            String resolvedCatalog = StringUtils.isNotBlank(catalog) ? catalog.trim() : scope.catalog();
            return new Endpoint(emptyToNull(resolvedCatalog), schema.trim(), table, true);
        }
        return new Endpoint(catalog.trim(), null, table, true);
    }

    private static TableRef tableRef(Scope scope, Map<String, String> localNames, String catalog, String schema, String table) {
        if (StringUtils.isBlank(table)) {
            return null;
        }
        if (sameNamespace(scope, catalog, schema)) {
            if (localNames == null) {
                return null;
            }
            String canonical = localNames.get(norm(table));
            if (canonical == null) {
                return null;
            }
            return new TableRef(scope.catalog(), scope.schema(), canonical);
        }
        if (scope.schemaScoped()) {
            if (StringUtils.isBlank(schema)) {
                return null;
            }
            String resolvedCatalog = StringUtils.isNotBlank(catalog) ? catalog.trim() : scope.catalog();
            return new TableRef(emptyToNull(resolvedCatalog), schema.trim(), table.trim());
        }
        if (StringUtils.isBlank(catalog)) {
            return null;
        }
        return new TableRef(catalog.trim(), null, table.trim());
    }

    private static String emptyToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private static String tableId(Scope scope, TableRef table) {
        if (sameNamespace(scope, table.catalog(), table.schema())) {
            return "local\0" + norm(table.table());
        }
        return externalIdentity(scope, table.catalog(), table.schema(), table.table());
    }

    private static String endpointId(Scope scope, Endpoint endpoint) {
        if (!endpoint.external()) {
            return "local\0" + norm(endpoint.table());
        }
        return externalIdentity(scope, endpoint.catalog(), endpoint.schema(), endpoint.table());
    }

    private static String externalIdentity(Scope scope, String catalog, String schema, String table) {
        if (scope.schemaScoped()) {
            return norm(schema) + '\0' + norm(table);
        }
        return norm(catalog) + '\0' + norm(table);
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isForeignKey(ConstraintMetadata constraint) {
        if (constraint == null || StringUtils.isBlank(constraint.referencedTable())) {
            return false;
        }
        if (StringUtils.isBlank(constraint.type())) {
            return true;
        }
        String type = constraint.type().trim().toUpperCase(Locale.ROOT);
        return "R".equals(type) || "F".equals(type) || type.contains("FOREIGN");
    }

    private static boolean isCrossNamespace(boolean schemaScoped, String catalog, String schema, String referencedNamespace) {
        if (StringUtils.isBlank(referencedNamespace)) {
            return false;
        }
        String current = schemaScoped ? schema : catalog;
        return StringUtils.isNotBlank(current) && !referencedNamespace.trim().equalsIgnoreCase(current.trim());
    }

    private static final class ConstraintGroup {
        private final String name;
        private final Endpoint from;
        private final Endpoint to;
        private final Map<String, ColumnPair> pairs = new LinkedHashMap<>();
        private int fallbackSeq = 1;

        private ConstraintGroup(String name, Endpoint from, Endpoint to) {
            this.name = name;
            this.from = from;
            this.to = to;
        }

        private void addPair(String fromColumn, String toColumn, int keySeq) {
            String id = norm(fromColumn) + '\0' + norm(toColumn);
            if (pairs.containsKey(id)) {
                return;
            }
            int seq = keySeq > 0 ? keySeq : fallbackSeq;
            fallbackSeq++;
            pairs.put(id, new ColumnPair(fromColumn, toColumn, seq));
        }

        private List<String> fromColumns() {
            return ordered().stream().map(ColumnPair::fromColumn).toList();
        }

        private List<String> toColumns() {
            return ordered().stream().map(ColumnPair::toColumn).toList();
        }

        private List<ColumnPair> ordered() {
            List<ColumnPair> values = new ArrayList<>(pairs.values());
            values.sort(Comparator.comparingInt(ColumnPair::seq));
            return values;
        }
    }

    private record ColumnPair(String fromColumn, String toColumn, int seq) {
    }
}
