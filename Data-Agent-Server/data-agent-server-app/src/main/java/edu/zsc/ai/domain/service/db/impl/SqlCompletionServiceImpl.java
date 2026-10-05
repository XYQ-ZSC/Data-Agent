package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.SqlCompletionItem;
import edu.zsc.ai.domain.model.dto.response.db.SqlCompletionResponse;
import edu.zsc.ai.domain.service.db.*;
import edu.zsc.ai.plugin.Plugin;
import edu.zsc.ai.plugin.capability.SqlCompleter;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.metadata.*;
import edu.zsc.ai.plugin.model.sql.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.*;
import java.util.function.Supplier;

/** Dialect analysis plus account-visible metadata. Every table reference keeps its own binding. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlCompletionServiceImpl implements SqlCompletionService {
    private static final int MAX_ITEMS = 500;
    private final ConnectionService connectionService;
    private final DbConnectionService dbConnectionService;
    private final ConnectionAccessService accessService;
    private final SqlCompletionMetadataCache cache;

    private SqlCompleter dialect(Long connectionId) {
        accessService.assertWorkbenchApiAllowed();
        accessService.assertReadable(connectionId);
        var config = dbConnectionService.getById(connectionId);
        if (config == null) throw new IllegalArgumentException("Connection does not exist");
        Plugin plugin = DefaultPluginManager.getInstance().getPluginByDbTypeAndVersion(config.getDbType(), null);
        return plugin instanceof SqlCompleter completer ? completer : null;
    }

    @Override
    public SqlCompletionResponse dictionary(Long connectionId) {
        SqlCompleter completer = dialect(connectionId);
        if (completer == null) return new SqlCompletionResponse(List.of());
        CompletionContext context = completer.analyzeCompletionContext("", 0);
        List<SqlCompletionItem> items = new ArrayList<>();
        addStatic(completer, context, "", items);
        return finish(items, context, Integer.MAX_VALUE);
    }

    @Override
    public void invalidate(Long connectionId) {
        dialect(connectionId); // Always check access, including cache hits/invalidation.
        cache.invalidate(connectionId);
    }

    @Override
    public SqlCompletionResponse complete(DbContext db, String sql, int cursorOffset) {
        SqlCompleter completer = dialect(db.connectionId());
        if (completer == null) return new SqlCompletionResponse(List.of());
        sql = sql == null ? "" : sql;
        CompletionContext context = completer.analyzeCompletionContext(sql, cursorOffset);
        if (context.scope() == CompletionScope.NONE) return new SqlCompletionResponse(List.of());
        List<SqlCompletionItem> items = new ArrayList<>();
        addStatic(completer, context, sql, items);
        if (context.scope() != CompletionScope.KEYWORD) {
            try {
                connectionService.openConnection(db);
                var active = ActiveConnectionRegistry.getOwnedConnection(db);
                SqlCompleter connected = DefaultPluginManager.getInstance().getSqlCompleterByPluginId(active.pluginId());
                if (connected != null) {
                    completer = connected;
                    context = completer.analyzeCompletionContext(sql, cursorOffset);
                    items.clear();
                    addStatic(completer, context, sql, items);
                }
                try (var borrowed = active.borrowConnection()) {
                    new Candidates(db, active.pluginId(), active.dataSource(), borrowed.connection(), completer, context, sql, items).build();
                }
            } catch (RuntimeException e) {
                // Static items already exist, even if the database is unavailable.
                log.debug("SQL completion metadata unavailable: {}", e.getMessage());
            }
        }
        return finish(items, context, MAX_ITEMS);
    }

    private static void addStatic(SqlCompleter dialect, CompletionContext context, String sql,
                                  List<SqlCompletionItem> items) {
        if (context.quotedPrefix()) return;
        if (context.scope() != CompletionScope.KEYWORD && context.scope() != CompletionScope.COLUMN
                && context.scope() != CompletionScope.FUNCTION) return;
        if (context.scope() != CompletionScope.FUNCTION) {
            for (String word : dialect.completionKeywords()) {
                int rank = word.equals("SELECT") ? 100 : 110;
                items.add(item(word, "KEYWORD", word, "DM keyword", context, "static", word, rank, null, false));
            }
        }
        for (CompletionFunction function : dialect.functionTemplates()) {
            boolean parentheses = followsParen(sql, context.replaceEnd());
            items.add(item(function.name(), "FUNCTION", parentheses ? function.name() : function.insertText(),
                    function.signature(), context, "static", function.name(), 120,
                    function.signature(), function.snippet() && !parentheses));
        }
        if (context.scope() == CompletionScope.KEYWORD) {
            String[][] templates = {
                    {"sel", "SELECT ${2:*}\nFROM ${1:table};", "Select data from a table"},
                    {"selc", "SELECT COUNT(*)\nFROM ${1:table}\nWHERE ${2:condition};", "Select data from a table with a condition"},
                    {"ins", "INSERT INTO ${1:table} (${2:columns})\nVALUES (${3:values});", "Insert data into a table"},
                    {"upd", "UPDATE ${1:table}\nSET ${2:column} = ${3:value}\nWHERE ${4:condition};", "Update data in a table"},
                    {"del", "DELETE FROM ${1:table}\nWHERE ${2:condition};", "Delete data from a table"}
            };
            for (String[] template : templates) items.add(item(template[0], "SNIPPET", template[1],
                    template[2], context, "static", template[0], 90, template[2], true));
        }
    }

    private final class Candidates {
        final DbContext db;
        final String plugin;
        final Connection connection;
        final Object metadataSession;
        final SqlCompleter dialect;
        final CompletionContext context;
        final String sql;
        final List<SqlCompletionItem> items;
        final DefaultPluginManager managers = DefaultPluginManager.getInstance();
        final Map<CompletionRelation, List<Field>> resolved = new IdentityHashMap<>();
        Candidates(DbContext db, String plugin, Object metadataSession, Connection connection, SqlCompleter dialect,
                   CompletionContext context, String sql, List<SqlCompletionItem> items) {
            this.db = db; this.plugin = plugin; this.connection = connection; this.dialect = dialect;
            this.metadataSession = metadataSession;
            this.context = context; this.sql = sql; this.items = items;
        }
        void build() {
            switch (context.scope()) {
                case TABLE -> tables(db, true);
                case JOIN -> { tables(db, true); joins(); }
                case QUALIFIED -> qualified();
                case COLUMN, INSERT_COLUMN, UPDATE_COLUMN -> {
                    columns(context.relations(), false);
                    if (context.scope() == CompletionScope.COLUMN) routines(false);
                }
                case FUNCTION -> routines(false);
                case PROCEDURE -> routines(true);
                default -> { }
            }
        }
        String name(String value) { return dialect.identifierInsertText(value, false); }
        String name(CompletionIdentifier value) { return dialect.identifierInsertText(value.name(), value.quoted()); }
        DbContext schema(String value) { return new DbContext(db.connectionId(), db.catalog(), value); }
        <T> List<T> cached(DbContext context, String kind, String object, Supplier<List<T>> loader) {
            return quietly(() -> cache.get(plugin, context, kind, object, metadataSession, loader));
        }
        List<Field> resolve(CompletionRelation relation, int depth) {
            if (depth > 32) return List.of();
            if (resolved.containsKey(relation)) return resolved.get(relation);
            resolved.put(relation, List.of());
            List<Field> fields;
            if (relation.logical()) {
                fields = queryFields(relation.query(), depth + 1);
            } else if (relation.table() != null) {
                CompletionTableRef table = relation.table();
                DbContext source = schema(table.schema() == null ? db.schema() : table.schema());
                fields = cached(source, "columns", table.name(), () -> managers.getColumnManagerByPluginId(plugin)
                        .getColumns(connection, source.catalog(), source.schema(), table.name())).stream()
                        .map(c -> new Field(new CompletionIdentifier(c.name(), false), c.typeName(), c.remarks())).toList();
            } else fields = List.of();
            resolved.put(relation, fields);
            return fields;
        }
        List<Field> queryFields(CompletionQuery query, int depth) {
            if (query == null || depth > 32) return List.of();
            List<Field> output = new ArrayList<>();
            for (CompletionProjection projection : query.projections()) {
                List<CompletionRelation> sources = query.relations().stream()
                        .filter(r -> projection.qualifier() == null
                                || r.qualifier().name().equals(projection.qualifier().name())).toList();
                if (projection.wildcard()) {
                    for (CompletionRelation source : sources) output.addAll(resolve(source, depth + 1));
                } else if (projection.output() != null) {
                    Field origin = sources.stream().flatMap(r -> resolve(r, depth + 1).stream())
                            .filter(f -> projection.column() != null && f.id().name().equals(projection.column().name()))
                            .findFirst().orElse(null);
                    output.add(new Field(projection.output(), origin == null ? null : origin.type(),
                            origin == null ? null : origin.remarks()));
                }
            }
            if (!query.explicitColumns().isEmpty()) {
                List<Field> renamed = new ArrayList<>();
                for (int i = 0; i < query.explicitColumns().size(); i++) {
                    Field field = i < output.size() ? output.get(i) : null;
                    renamed.add(new Field(query.explicitColumns().get(i), field == null ? null : field.type(),
                            field == null ? null : field.remarks()));
                }
                return renamed;
            }
            return output;
        }
        void tables(DbContext source, boolean ctes) {
            if (ctes) context.commonTables().keySet().forEach(cte -> add(cte, "TABLE", name(cte),
                    "CTE", "parser", cte, 10, null, false));
            for (String table : cached(source, "tables", null, () -> managers.getTableManagerByPluginId(plugin)
                    .getTableNames(connection, source.catalog(), source.schema()))) {
                add(table, "TABLE", name(table), "table · " + source.schema(), "metadata", table, 20, null, false);
            }
            for (String view : cached(source, "views", null, () -> managers.getViewManagerByPluginId(plugin)
                    .getViews(connection, source.catalog(), source.schema()))) {
                add(view, "VIEW", name(view), "view · " + source.schema(), "metadata", view, 21, null, false);
            }
            if (ctes && managers.supportsSchemaByPluginId(plugin)) {
                for (String value : cached(db, "schemas", null, () -> managers.getSchemaManagerByPluginId(plugin)
                        .getSchemas(connection, db.catalog()))) add(value, "SCHEMA", name(value), "schema",
                        "metadata", value, 30, null, false);
            }
        }
        void qualified() {
            if (context.qualifiedTable() != null) {
                var table = context.qualifiedTable();
                columns(List.of(new CompletionRelation(new CompletionIdentifier(table.name(), false),
                        table, null, false, false)), true);
                return;
            }
            String qualifier = context.qualifier();
            if (qualifier == null) return;
            CompletionRelation bound = context.relations().stream()
                    .filter(r -> r.qualifier().name().equals(qualifier)).findFirst().orElse(null);
            if (bound != null) { columns(List.of(bound), true); return; }
            if (context.commonTables().containsKey(qualifier)) {
                columns(List.of(new CompletionRelation(new CompletionIdentifier(qualifier, false), null,
                        context.commonTables().get(qualifier), true, false)), true);
                return;
            }
            // Do not perform a case-insensitive fallback for quoted names.
            if (context.selectList()) columns(List.of(new CompletionRelation(new CompletionIdentifier(qualifier, false),
                    new CompletionTableRef(null, qualifier), null, false, false)), true);
            tables(schema(qualifier), false);
            routines(schema(qualifier), false);
            routines(schema(qualifier), true);
        }
        void columns(List<CompletionRelation> relations, boolean qualified) {
            Map<String, Integer> owners = new HashMap<>();
            for (var relation : relations) for (Field field : resolve(relation, 0)) owners.merge(field.id().name(), 1, Integer::sum);
            List<String> all = new ArrayList<>();
            for (var relation : relations) {
                for (Field field : resolve(relation, 0)) {
                    if (context.excludedColumns().contains(field.id().name())) continue;
                    String insertion = name(field.id());
                    if (!qualified && owners.get(field.id().name()) > 1) insertion = name(relation.qualifier()) + "." + insertion;
                    // The existing qualifier remains before the replacement range for the first
                    // field; subsequent fields need their own qualifier after each comma.
                    all.add(qualified && !all.isEmpty() ? name(relation.qualifier()) + "." + insertion : insertion);
                    boolean assignment = context.scope() == CompletionScope.UPDATE_COLUMN;
                    if (assignment) insertion = snippetLiteral(insertion) + " = ${1:value}";
                    String detail = (field.type() == null ? "" : field.type() + " · ") + relation.qualifier().name();
                    add(field.id().name(), "COLUMN", insertion, detail, relation.logical() ? "parser" : "metadata",
                            field.id().name(), 0, field.remarks(), assignment);
                }
                if (!qualified && context.scope() == CompletionScope.COLUMN && relation.aliased())
                    add(relation.qualifier().name(), "ALIAS", name(relation.qualifier()), "table alias", "parser",
                            relation.qualifier().name(), 40, null, false);
            }
            String before = sql.substring(0, context.replaceStart()).stripTrailing();
            boolean projectionStart = before.endsWith(".") || before.endsWith(",")
                    || before.matches("(?is).*\\bSELECT(?:\\s+(?:DISTINCT|ALL))?");
            if (context.selectList() && !context.quotedPrefix() && projectionStart && all.size() > 1) add("* (all columns)", "ALL_COLUMNS", String.join(", ", all),
                    all.size() + " columns", "parser", "*", 5, null, false);
        }
        void routines(boolean procedure) {
            routines(db, procedure);
        }
        void routines(DbContext source, boolean procedure) {
            if (procedure) {
                for (ProcedureMetadata value : cached(source, "procedures", null, () -> managers.getProcedureManagerByPluginId(plugin)
                        .getProcedures(connection, source.catalog(), source.schema()))) routine(value.name(), value.parameters(), null, true);
            } else {
                for (FunctionMetadata value : cached(source, "functions", null, () -> managers.getFunctionManagerByPluginId(plugin)
                        .getFunctions(connection, source.catalog(), source.schema()))) routine(value.name(), value.parameters(), value.returnType(), false);
            }
        }
        void routine(String value, List<ParameterInfo> parameters, String returnType, boolean procedure) {
            String args = parameters == null ? "${1:}" : java.util.stream.IntStream.range(0, parameters.size())
                    .mapToObj(i -> "${" + (i+1) + ":" + snippetLiteral(Objects.toString(parameters.get(i).name(), "arg" + (i+1))) + "}")
                    .collect(java.util.stream.Collectors.joining(", "));
            String signature = value + "(" + (parameters == null ? "…" : parameters.stream()
                    .map(p -> p.name() + " " + p.dataType()).collect(java.util.stream.Collectors.joining(", "))) + ")"
                    + (returnType == null ? "" : " → " + returnType);
            boolean snippet = !followsParen(sql, context.replaceEnd());
            add(value, procedure ? "PROCEDURE" : "FUNCTION", (snippet ? snippetLiteral(name(value)) : name(value)) + (snippet ? "(" + args + ")" : ""),
                    signature, "metadata", value, 50, signature, snippet);
        }
        void joins() {
            for (var source : context.relations()) {
                if (source.logical() || source.table() == null) continue;
                CompletionTableRef table = source.table();
                DbContext sourceDb = schema(table.schema() == null ? db.schema() : table.schema());
                for (ConstraintMetadata fk : cached(sourceDb, "constraints", table.name(), () -> managers.getConstraintManagerByPluginId(plugin)
                        .getConstraints(connection, sourceDb.catalog(), sourceDb.schema(), table.name()))) {
                    if (!fk.enabled() || fk.referencedTable() == null || fk.columns() == null
                            || fk.referencedColumns() == null || fk.columns().isEmpty()
                            || fk.columns().size() != fk.referencedColumns().size()) continue;
                    join(source, fk.referencedSchema(), fk.referencedTable(), fk.referencedColumns(), fk.columns(), fk.name());
                }
                for (ForeignJoin fk : cached(sourceDb, "exportedKeys", table.name(), () -> exported(sourceDb, table.name())))
                    join(source, fk.schema(), fk.table(), fk.columns(), fk.referencedColumns(), fk.name());
            }
        }
        List<ForeignJoin> exported(DbContext source, String table) {
            record Key(String schema, String table, String name) {}
            Map<Key, SortedMap<Integer, String[]>> groups = new LinkedHashMap<>();
            try (ResultSet rs = connection.getMetaData().getExportedKeys(source.catalog(), source.schema(), table)) {
                while (rs.next()) {
                    String constraint = rs.getString("FK_NAME");
                    if (constraint == null) continue; // Cannot safely group unnamed composite constraints.
                    Key key = new Key(rs.getString("FKTABLE_SCHEM"), rs.getString("FKTABLE_NAME"), constraint);
                    groups.computeIfAbsent(key, ignored -> new TreeMap<>()).put(rs.getInt("KEY_SEQ"),
                            new String[]{rs.getString("FKCOLUMN_NAME"), rs.getString("PKCOLUMN_NAME")});
                }
            } catch (Exception e) { log.debug("Exported foreign key completion unavailable: {}", e.getMessage()); }
            return groups.entrySet().stream().map(e -> new ForeignJoin(e.getKey().schema(), e.getKey().table(), e.getKey().name(),
                    e.getValue().values().stream().map(pair -> pair[0]).toList(), e.getValue().values().stream().map(pair -> pair[1]).toList())).toList();
        }
        void join(CompletionRelation source, String targetSchema, String targetTable,
                  List<String> targetColumns, List<String> sourceColumns, String constraint) {
            String base = targetTable.replaceAll("[^\\p{L}\\p{N}_]", "").toUpperCase(Locale.ROOT);
            if (base.isEmpty() || Character.isDigit(base.codePointAt(0))) base = "T";
            Set<String> used = new HashSet<>();
            context.relations().forEach(r -> used.add(r.qualifier().name()));
            String initial = base.substring(0, base.offsetByCodePoints(0, 1));
            String alias = initial;
            for (int suffix = 1; used.contains(alias); suffix++) alias = initial + suffix;
            String target = (targetSchema == null || targetSchema.equals(db.schema()) ? "" : name(targetSchema) + ".") + name(targetTable);
            List<String> conditions = new ArrayList<>();
            for (int i = 0; i < targetColumns.size(); i++) conditions.add(name(alias) + "." + name(targetColumns.get(i))
                    + " = " + name(source.qualifier()) + "." + name(sourceColumns.get(i)));
            add(targetTable + " · " + constraint, "JOIN", target + " " + name(alias) + " ON " + String.join(" AND ", conditions),
                    "foreign key · " + constraint, "metadata", targetTable, 0, String.join(" AND ", conditions), false);
        }
        void add(String label, String kind, String insert, String detail, String source,
                 String filter, int rank, String documentation, boolean snippet) {
            items.add(item(label, kind, insert, detail, context, source, filter, rank, documentation, snippet));
        }
    }
    private record Field(CompletionIdentifier id, String type, String remarks) {}
    private record ForeignJoin(String schema, String table, String name, List<String> columns, List<String> referencedColumns) {}
    private static <T> List<T> quietly(Supplier<List<T>> action) {
        try { return action.get(); } catch (RuntimeException e) { log.debug("Completion metadata unavailable: {}", e.getMessage()); return List.of(); }
    }
    private static boolean followsParen(String sql, int end) { return end <= sql.length() && sql.substring(end).stripLeading().startsWith("("); }
    private static String snippetLiteral(String text) { return text.replace("\\", "\\\\").replace("$", "\\$").replace("}", "\\}"); }
    private static SqlCompletionItem item(String label, String kind, String insert, String detail, CompletionContext context,
                                           String source, String filter, int rank, String documentation, boolean snippet) {
        return new SqlCompletionItem(label, kind, insert, detail, context.replaceStart(), context.replaceEnd(),
                source, context.quotedPrefix() ? "\"" + filter.replace("\"", "\"\"") + "\"" : filter,
                rank, documentation, snippet);
    }
    private static SqlCompletionResponse finish(List<SqlCompletionItem> items, CompletionContext context, int limit) {
        Map<List<Object>, SqlCompletionItem> unique = new LinkedHashMap<>();
        for (SqlCompletionItem value : items) {
            String prefix = context.prefix();
            String filter = context.quotedPrefix() ? value.filterText().substring(1, value.filterText().length()-1).replace("\"\"", "\"") : value.filterText();
            boolean matches = context.quotedPrefix() ? filter.startsWith(prefix)
                    : value.filterText().regionMatches(true, 0, prefix, 0, prefix.length());
            if (matches) unique.putIfAbsent(List.of(value.kind(), value.insertText(), value.replaceStart(), value.replaceEnd()), value);
        }
        List<SqlCompletionItem> sorted = unique.values().stream().sorted(Comparator.comparingInt(SqlCompletionItem::rank)
                .thenComparing(SqlCompletionItem::label, String.CASE_INSENSITIVE_ORDER).thenComparing(SqlCompletionItem::insertText)).toList();
        return new SqlCompletionResponse(sorted.stream().limit(limit).toList(), sorted.size() > limit);
    }
}
