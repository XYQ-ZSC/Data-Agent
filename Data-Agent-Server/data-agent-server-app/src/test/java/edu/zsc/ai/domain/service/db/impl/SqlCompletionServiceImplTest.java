package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.*;
import edu.zsc.ai.domain.model.entity.db.DbConnection;
import edu.zsc.ai.domain.service.db.*;
import edu.zsc.ai.plugin.capability.*;
import edu.zsc.ai.plugin.dm.Dm8Plugin;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.metadata.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.sql.Connection;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlCompletionServiceImplTest {
    final DbContext db = new DbContext(1L, null, "APP");
    ConnectionService connections;
    ConnectionAccessService access;
    ColumnManager columns;
    FunctionManager functions;
    ProcedureManager procedures;
    ConstraintManager constraints;
    Connection connection;
    SqlCompletionServiceImpl service;
    MockedStatic<DefaultPluginManager> managers;
    MockedStatic<ActiveConnectionRegistry> registry;
    @BeforeEach void setup() {
        connections = mock(ConnectionService.class); access = mock(ConnectionAccessService.class);
        DbConnectionService configs = mock(DbConnectionService.class);
        when(configs.getById(1L)).thenReturn(DbConnection.builder().id(1L).dbType("dm").build());
        var manager = mock(DefaultPluginManager.class); var dm = new Dm8Plugin();
        when(manager.getPluginByDbTypeAndVersion("dm", null)).thenReturn(dm);
        when(manager.getSqlCompleterByPluginId("dm-8")).thenReturn(dm);
        columns = mock(ColumnManager.class); var tables = mock(TableManager.class);
        functions = mock(FunctionManager.class); procedures = mock(ProcedureManager.class); constraints = mock(ConstraintManager.class);
        when(manager.getColumnManagerByPluginId("dm-8")).thenReturn(columns);
        when(manager.getTableManagerByPluginId("dm-8")).thenReturn(tables);
        when(manager.getViewManagerByPluginId("dm-8")).thenReturn(mock(ViewManager.class));
        when(manager.getFunctionManagerByPluginId("dm-8")).thenReturn(functions);
        when(manager.getProcedureManagerByPluginId("dm-8")).thenReturn(procedures);
        when(manager.getConstraintManagerByPluginId("dm-8")).thenReturn(constraints);
        var active = mock(ActiveConnectionRegistry.ActiveConnection.class);
        var borrowed = mock(ActiveConnectionRegistry.BorrowedConnection.class);
        when(active.pluginId()).thenReturn("dm-8"); when(active.borrowConnection()).thenReturn(borrowed);
        connection = mock(Connection.class); when(borrowed.connection()).thenReturn(connection);
        when(tables.getTableNames(connection, null, "APP")).thenReturn(List.of("EMPLOYEE", "DEPARTMENT", "Mixed Table"));
        when(columns.getColumns(connection, null, "APP", "EMPLOYEE")).thenReturn(fields("ID", "NAME", "First Name", "MiX"));
        when(columns.getColumns(connection, null, "APP", "DEPARTMENT")).thenReturn(fields("ID", "TITLE"));
        managers = mockStatic(DefaultPluginManager.class); managers.when(DefaultPluginManager::getInstance).thenReturn(manager);
        registry = mockStatic(ActiveConnectionRegistry.class); registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(db)).thenReturn(active);
        service = new SqlCompletionServiceImpl(connections, configs, access, new SqlCompletionMetadataCache());
    }
    @AfterEach void close() { registry.close(); managers.close(); }
    static List<ColumnMetadata> fields(String... names) {
        return IntStream.range(0, names.length).mapToObj(i -> new ColumnMetadata(names[i], 12, "VARCHAR", 100, 0, true, i+1, "note " + names[i])).toList();
    }
    SqlCompletionResponse complete(String marked) {
        return service.complete(db, marked.replace("|", ""), marked.indexOf('|'));
    }
    List<SqlCompletionItem> kind(String sql, String kind) { return complete(sql).items().stream().filter(i -> kind.equals(i.kind())).toList(); }
    SqlCompletionItem column(String sql, String label) { return kind(sql, "COLUMN").stream().filter(i -> i.label().equals(label)).findFirst().orElseThrow(); }
    String apply(String marked, SqlCompletionItem item) {
        String sql = marked.replace("|", ""); return sql.substring(0, item.replaceStart()) + item.insertText() + sql.substring(item.replaceEnd());
    }
    @Test void closedQuotedWordUsesOnlyPrefixAndReplacesWholeToken() {
        String sql = "SELECT e.\"First |Old\" FROM EMPLOYEE e"; var item = column(sql, "First Name");
        assertEquals("SELECT e.\"First Name\" FROM EMPLOYEE e", apply(sql, item)); assertEquals("note First Name", item.documentation());
        assertEquals("\"First Name\"", item.filterText());
    }
    @Test void unquotedMiddleCursorIgnoresSuffix() {
        String sql = "SELECT * FROM EM|X";
        var item = complete(sql).items().stream().filter(i -> i.label().equals("EMPLOYEE")).findFirst().orElseThrow();
        assertEquals("SELECT * FROM EMPLOYEE", apply(sql, item));
    }
    @Test void escapedAliasAndSelfJoinKeepSeparateOwners() {
        var escaped = kind("SELECT | FROM EMPLOYEE \"a\"\"b\", DEPARTMENT d", "COLUMN");
        assertTrue(escaped.stream().anyMatch(i -> i.insertText().equals("\"a\"\"b\".\"ID\"")));
        var self = kind("SELECT | FROM EMPLOYEE a JOIN EMPLOYEE b ON a.ID=b.ID", "COLUMN");
        assertTrue(self.stream().anyMatch(i -> i.insertText().equals("\"A\".\"ID\"")));
        assertTrue(self.stream().anyMatch(i -> i.insertText().equals("B.\"ID\"")));
        assertFalse(self.stream().anyMatch(i -> i.insertText().equals("\"ID\"")));
    }
    @Test void quotedAliasDoesNotMatchDifferentCase() {
        assertTrue(kind("SELECT foo.| FROM EMPLOYEE \"foo\"", "COLUMN").isEmpty());
        assertEquals(4, kind("SELECT \"foo\".| FROM EMPLOYEE \"foo\"", "COLUMN").size());
    }
    @Test void nestedCteAndDerivedWildcardsExpandWithTypes() {
        var item = column("WITH q AS (SELECT e.* FROM EMPLOYEE e), r AS (SELECT * FROM q) SELECT d.| FROM (SELECT * FROM r) d", "ID");
        assertTrue(item.detail().startsWith("VARCHAR"));
        assertEquals(4, kind("WITH q AS (SELECT * FROM EMPLOYEE) SELECT c.| FROM q c", "COLUMN").size());
    }
    @Test void cteExplicitNamesAndQuotedNamesAreRespected() {
        assertEquals(List.of("NEW_ID", "NEW_NAME"), kind("WITH q(NEW_ID, NEW_NAME) AS (SELECT ID, NAME FROM EMPLOYEE) SELECT a.| FROM q a", "COLUMN").stream().map(SqlCompletionItem::label).toList());
        assertEquals(List.of("ID"), kind("WITH \"q\" AS (SELECT ID FROM EMPLOYEE), Q AS (SELECT TITLE FROM DEPARTMENT) SELECT x.| FROM \"q\" x", "COLUMN").stream().map(SqlCompletionItem::label).toList());
    }
    @Test void castUsesProjectionAliasInsteadOfType() {
        assertEquals(List.of("CODE"), kind("SELECT d.| FROM (SELECT CAST(ID AS VARCHAR(20)) AS CODE FROM EMPLOYEE) d", "COLUMN").stream().map(SqlCompletionItem::label).toList());
    }
    @Test void recursiveCteTerminates() {
        assertEquals(List.of("ID"), kind("WITH q(ID) AS (SELECT ID FROM EMPLOYEE UNION ALL SELECT ID FROM q) SELECT a.| FROM q a", "COLUMN").stream().map(SqlCompletionItem::label).toList());
    }
    @Test void intermediateScopeVisibleAndNearestBindingWinsAcrossRelationTypes() {
        assertEquals(List.of("ID", "TITLE"), kind("SELECT * FROM EMPLOYEE a WHERE EXISTS (SELECT * FROM DEPARTMENT b WHERE EXISTS (SELECT b.| FROM EMPLOYEE c))", "COLUMN").stream().map(SqlCompletionItem::label).toList());
        assertEquals(List.of("TITLE"), kind("SELECT * FROM EMPLOYEE a WHERE EXISTS (SELECT a.| FROM (SELECT TITLE FROM DEPARTMENT) a)", "COLUMN").stream().map(SqlCompletionItem::label).toList());
        assertEquals(4, kind("SELECT * FROM (SELECT TITLE FROM DEPARTMENT) a WHERE EXISTS (SELECT a.| FROM EMPLOYEE a)", "COLUMN").size());
        assertTrue(kind("SELECT a.ID FROM EMPLOYEE a UNION ALL SELECT a.| FROM DEPARTMENT b", "COLUMN").isEmpty());
    }
    @Test void insertAndUpdateExcludeUsedFieldsAndUpdateProvidesAssignmentSnippet() {
        assertFalse(kind("INSERT INTO EMPLOYEE (ID, |)", "COLUMN").stream().anyMatch(i -> i.label().equals("ID")));
        assertTrue(kind("INSERT INTO EMPLOYEE (ID, |)", "COLUMN").stream().anyMatch(i -> i.label().equals("NAME")));
        var item = column("UPDATE EMPLOYEE SET ID = 1, |", "NAME");
        assertEquals("\"NAME\" = ${1:value}", item.insertText()); assertTrue(item.snippet());
        assertFalse(kind("UPDATE EMPLOYEE SET ID = 1, |", "COLUMN").stream().anyMatch(i -> i.label().equals("ID")));
    }
    @Test void alterTableAndAllColumnsUseCorrectContextAndQuoting() {
        assertFalse(kind("ALTER TABLE |", "TABLE").isEmpty());
        assertEquals("\"ID\", \"NAME\", \"First Name\", \"MiX\"", kind("SELECT | FROM EMPLOYEE e", "ALL_COLUMNS").get(0).insertText());
        assertTrue(kind("SELECT * FROM EMPLOYEE e WHERE |", "ALL_COLUMNS").isEmpty());
    }
    @Test void dictionaryAndStatementStartDoNotOpenJdbc() {
        assertTrue(service.dictionary(1L).items().stream().anyMatch(i -> i.kind().equals("SNIPPET") && i.label().equals("sel")));
        assertFalse(complete("sel|").items().isEmpty()); verifyNoInteractions(connections); verify(access, times(2)).assertReadable(1L);
    }
    @Test void conditionalSelectTemplateProvidesTableThenConditionTabStops() {
        var template = kind("selc|", "SNIPPET").stream().filter(i -> i.label().equals("selc")).findFirst().orElseThrow();
        assertTrue(template.snippet());
        assertEquals("SELECT COUNT(*)\nFROM ${1:table}\nWHERE ${2:condition};", apply("selc|", template));
    }
    @Test void offlineDatabaseRetainsStaticCandidates() {
        when(connections.openConnection(db)).thenThrow(new IllegalStateException("offline"));
        assertTrue(complete("SELECT NV|").items().stream().anyMatch(i -> i.label().equals("NVL")));
    }
    @Test void functionsDoNotDuplicateParenthesesOrAddThemToSysdate() {
        var item = complete("SELECT NV|L()").items().stream().filter(i -> i.label().equals("NVL")).findFirst().orElseThrow();
        assertEquals("SELECT NVL()", apply("SELECT NV|L()", item)); assertFalse(item.snippet());
        assertEquals("SYSDATE", complete("SELECT SYS|").items().stream().filter(i -> i.label().equals("SYSDATE") && i.kind().equals("FUNCTION")).findFirst().orElseThrow().insertText());
    }
    @Test void customRoutinesHaveParametersAndReturnTypes() {
        when(functions.getFunctions(any(), isNull(), eq("APP"))).thenReturn(List.of(new FunctionMetadata("MyFunc", List.of(new ParameterInfo("ARG", "INTEGER")), "VARCHAR")));
        var item = complete("SELECT My|").items().stream().filter(i -> i.label().equals("MyFunc")).findFirst().orElseThrow();
        assertEquals("\"MyFunc\"(${1:ARG})", item.insertText()); assertTrue(item.detail().contains("INTEGER") && item.detail().contains("VARCHAR"));
        when(procedures.getProcedures(any(), isNull(), eq("APP"))).thenReturn(List.of(new ProcedureMetadata("DO_WORK", List.of())));
        assertEquals("DO_WORK()", kind("CALL DO|", "PROCEDURE").get(0).insertText());
    }
    @Test void compositeForeignKeyJoinPreservesOrderAndAvoidsAliasCollision() {
        when(constraints.getConstraints(any(), isNull(), eq("APP"), eq("EMPLOYEE"))).thenReturn(List.of(new ConstraintMetadata("FK_EMP_DEPT", "EMPLOYEE", "FOREIGN_KEY", List.of("DEPT_ID", "REGION_ID"), "APP", "DEPARTMENT", List.of("ID", "REGION"), null, true)));
        assertEquals("DEPARTMENT D1 ON D1.\"ID\" = D.DEPT_ID AND D1.REGION = D.REGION_ID", kind("SELECT * FROM EMPLOYEE d JOIN |", "JOIN").get(0).insertText());
    }
    @Test void cacheInvalidationReloadsAndAlwaysChecksAccess() {
        complete("SELECT e.| FROM EMPLOYEE e"); complete("SELECT e.| FROM EMPLOYEE e");
        verify(columns, times(1)).getColumns(any(), isNull(), eq("APP"), eq("EMPLOYEE"));
        service.invalidate(1L); complete("SELECT e.| FROM EMPLOYEE e");
        verify(columns, times(2)).getColumns(any(), isNull(), eq("APP"), eq("EMPLOYEE")); verify(access, times(4)).assertReadable(1L);
    }
    @Test void truncationIsExplicitAndPrefixFetchesBeyondFirstPage() {
        when(columns.getColumns(any(), isNull(), eq("APP"), eq("EMPLOYEE"))).thenReturn(fields(IntStream.range(0, 600).mapToObj(i -> "COL" + String.format("%03d", i)).toArray(String[]::new)));
        var result = complete("SELECT e.| FROM EMPLOYEE e"); assertTrue(result.incomplete()); assertEquals(500, result.items().size());
        assertEquals("COL599", column("SELECT e.COL59| FROM EMPLOYEE e", "COL599").label());
    }
    @Test void literalsAndCommentsSuppressSuggestions() {
        for (String sql : List.of("SELECT 'x|y' FROM EMPLOYEE", "SELECT q'[x|y]' FROM EMPLOYEE", "-- abc|", "SELECT /* x|y */ ID")) assertTrue(complete(sql).items().isEmpty());
    }
    @Test void starExpansionReplacesExistingStarAndStaysOutOfExpressions() {
        String sql = "SELECT *| FROM EMPLOYEE e";
        var all = kind(sql, "ALL_COLUMNS").get(0);
        assertEquals("SELECT \"ID\", \"NAME\", \"First Name\", \"MiX\" FROM EMPLOYEE e", apply(sql, all));
        assertTrue(kind("SELECT SUM(|) FROM EMPLOYEE e", "ALL_COLUMNS").isEmpty());
        assertTrue(kind("SELECT ID *| FROM EMPLOYEE e", "ALL_COLUMNS").isEmpty());
        assertTrue(kind("SELECT \"*|\" FROM EMPLOYEE e", "ALL_COLUMNS").isEmpty());
    }
    @Test void quotedFieldsWithEscapesRemainMatchableAndSnippetIdentifiersAreLiteral() {
        when(columns.getColumns(any(), isNull(), eq("APP"), eq("EMPLOYEE"))).thenReturn(fields("a\"b", "$NAME"));
        String sql = "SELECT e.\"a\"\"|x\" FROM EMPLOYEE e";
        var escaped = column(sql, "a\"b");
        assertEquals("SELECT e.\"a\"\"b\" FROM EMPLOYEE e", apply(sql, escaped));
        assertEquals("\"a\"\"b\"", escaped.filterText());
        assertEquals("\\$NAME = ${1:value}", column("UPDATE EMPLOYEE SET |", "$NAME").insertText());
        when(functions.getFunctions(any(), isNull(), eq("APP"))).thenReturn(List.of(new FunctionMetadata("$F", null, "INT")));
        assertEquals("\\$F(${1:})", complete("SELECT $|").items().stream().filter(i -> i.kind().equals("FUNCTION") && i.label().equals("$F")).findFirst().orElseThrow().insertText());
    }

    @Test void qualifiedStarPrefixesEverySubsequentField() {
        String sql = "SELECT \"q\".*| FROM EMPLOYEE \"q\" JOIN DEPARTMENT d ON \"q\".ID=d.ID";
        var all = kind(sql, "ALL_COLUMNS").get(0);
        assertEquals("SELECT \"q\".\"ID\", \"q\".\"NAME\", \"q\".\"First Name\", \"q\".\"MiX\" FROM EMPLOYEE \"q\" JOIN DEPARTMENT d ON \"q\".ID=d.ID", apply(sql, all));
    }
    @Test void schemaQualifiedFunctionsAndProceduresAreReturned() {
        when(functions.getFunctions(any(), isNull(), eq("APP"))).thenReturn(List.of(new FunctionMetadata("MyFunc", List.of(), "INT")));
        when(procedures.getProcedures(any(), isNull(), eq("APP"))).thenReturn(List.of(new ProcedureMetadata("DO_WORK", List.of())));
        String sql = "SELECT APP.My|";
        var function = kind(sql, "FUNCTION").stream().filter(i -> i.label().equals("MyFunc")).findFirst().orElseThrow();
        assertEquals("SELECT APP.\"MyFunc\"()", apply(sql, function));
        assertEquals("CALL APP.DO_WORK()", apply("CALL APP.DO|", kind("CALL APP.DO|", "PROCEDURE").get(0)));
    }

    @Test void exportedCompositeForeignKeyUsesKeySequenceRatherThanResultOrder() throws Exception {
        var metadata = mock(java.sql.DatabaseMetaData.class);
        var keys = mock(java.sql.ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getExportedKeys(null, "APP", "DEPARTMENT")).thenReturn(keys);
        when(keys.next()).thenReturn(true, true, false);
        when(keys.getString("FK_NAME")).thenReturn("FK_EMP_DEPT");
        when(keys.getString("FKTABLE_SCHEM")).thenReturn("APP");
        when(keys.getString("FKTABLE_NAME")).thenReturn("EMPLOYEE");
        when(keys.getInt("KEY_SEQ")).thenReturn(2, 1);
        when(keys.getString("FKCOLUMN_NAME")).thenReturn("REGION_ID", "DEPT_ID");
        when(keys.getString("PKCOLUMN_NAME")).thenReturn("REGION", "ID");
        assertEquals("EMPLOYEE \"E\" ON \"E\".DEPT_ID = D.\"ID\" AND \"E\".REGION_ID = D.REGION", kind("SELECT * FROM DEPARTMENT d JOIN |", "JOIN").get(0).insertText());
        verify(keys).close();
    }
    @Test void staticSignaturesDistinguishUnknownParametersAndZeroArgumentFunctions() {
        var dictionary = service.dictionary(1L).items();
        assertEquals("UPPER(…)", dictionary.stream().filter(i -> i.label().equals("UPPER") && i.kind().equals("FUNCTION")).findFirst().orElseThrow().detail());
        assertEquals("ROW_NUMBER()", dictionary.stream().filter(i -> i.label().equals("ROW_NUMBER")).findFirst().orElseThrow().insertText());
    }

}
