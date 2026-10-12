package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.*;
import edu.zsc.ai.domain.model.entity.db.DbConnection;
import edu.zsc.ai.domain.service.db.*;
import edu.zsc.ai.plugin.capability.*;
import edu.zsc.ai.plugin.dm.Dm8Plugin;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.metadata.*;
import edu.zsc.ai.plugin.model.sql.*;
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
    TableManager tables;
    Dm8Plugin dm;
    Connection connection;
    SqlCompletionServiceImpl service;
    MockedStatic<DefaultPluginManager> managers;
    MockedStatic<ActiveConnectionRegistry> registry;
    @BeforeEach void setup() {
        connections = mock(ConnectionService.class); access = mock(ConnectionAccessService.class);
        DbConnectionService configs = mock(DbConnectionService.class);
        when(configs.getById(1L)).thenReturn(DbConnection.builder().id(1L).dbType("dm").build());
        var manager = mock(DefaultPluginManager.class); dm = spy(new Dm8Plugin());
        doReturn(new CompletionFunctionCatalog(dm.functionTemplates(),null,false)).when(dm).functionCatalog(any());
        doReturn(List.of()).when(dm).completionPackages(any(),any());
        doReturn(List.of()).when(dm).completionSynonyms(any(),any());
        when(manager.getPluginByDbTypeAndVersion("dm", null)).thenReturn(dm);
        when(manager.getSqlCompleterByPluginId("dm-8")).thenReturn(dm);
        columns = mock(ColumnManager.class); tables = mock(TableManager.class);
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
    @Test void offlineSignaturesIncludeTypesAndSpecialFunctionSyntax() {
        var dictionary = service.dictionary(1L).items();
        assertEquals("UPPER(text:VARCHAR/CLOB) → VARCHAR/CLOB", dictionary.stream().filter(i -> i.label().equals("UPPER") && i.kind().equals("FUNCTION")).findFirst().orElseThrow().detail());
        assertEquals("ROW_NUMBER()", dictionary.stream().filter(i -> i.label().equals("ROW_NUMBER")).findFirst().orElseThrow().insertText());
        assertTrue(dictionary.stream().filter(i -> i.kind().equals("FUNCTION")).allMatch(i -> i.detail().contains(" → ") && !i.detail().contains("…")));
        assertEquals("EXTRACT(${1:unit} FROM ${2:date})",dictionary.stream().filter(i -> i.label().equals("EXTRACT")).findFirst().orElseThrow().insertText());
    }
    @Test void orderByIncludesOutputAliasesButWhereAndSiblingQueriesDoNot() {
        var alias = column("SELECT ID AS EMP_ID FROM EMPLOYEE ORDER BY EMP_|","EMP_ID");
        assertEquals("SELECT ID AS EMP_ID FROM EMPLOYEE ORDER BY EMP_ID",apply("SELECT ID AS EMP_ID FROM EMPLOYEE ORDER BY EMP_|",alias));
        assertTrue(alias.detail().startsWith("VARCHAR"));
        assertEquals("\"Employee Key\"",column("SELECT ID AS \"Employee Key\" FROM EMPLOYEE ORDER BY \"Employee |\"","Employee Key").insertText());
        assertTrue(kind("SELECT ID AS EMP_ID FROM EMPLOYEE WHERE EMP_|","COLUMN").isEmpty());
        assertTrue(kind("SELECT ID AS OUTER_ID FROM EMPLOYEE WHERE EXISTS (SELECT TITLE FROM DEPARTMENT ORDER BY OUTER_|)","COLUMN").isEmpty());
    }
    @Test void keywordSuggestionsFollowSqlClauseAndDoNotLeakTemplatesOrFunctions() {
        assertEquals(List.of("BY"),kind("SELECT * FROM EMPLOYEE ORDER B|","KEYWORD").stream().map(SqlCompletionItem::label).toList());
        assertTrue(kind("SELECT * FROM EMPLOYEE ORDER B|","FUNCTION").isEmpty());
        assertEquals(List.of("TABLE"),kind("CREATE TA|","KEYWORD").stream().map(SqlCompletionItem::label).toList());
        assertTrue(kind("CREATE |","SNIPPET").isEmpty());
        assertTrue(kind("SELECT | FROM EMPLOYEE","KEYWORD").stream().noneMatch(i -> i.label().equals("CREATE") || i.label().equals("SELECT")));
        assertEquals(List.of("WHERE"),kind("SELECT * FROM EMPLOYEE W|","KEYWORD").stream().map(SqlCompletionItem::label).toList());
        assertEquals(List.of("ON"),kind("SELECT * FROM EMPLOYEE e JOIN DEPARTMENT d O|","KEYWORD").stream().map(SqlCompletionItem::label).toList());
        assertEquals(List.of("SET"),kind("UPDATE EMPLOYEE S|","KEYWORD").stream().map(SqlCompletionItem::label).toList());
        assertEquals(List.of("VALUES"),kind("INSERT INTO EMPLOYEE V|","KEYWORD").stream().map(SqlCompletionItem::label).toList());
        assertTrue(kind("SELECT SUM(ID |) FROM EMPLOYEE","KEYWORD").isEmpty());
        assertEquals(List.of("THEN"),kind("SELECT CASE WHEN ID = 1 TH| FROM EMPLOYEE","KEYWORD").stream().map(SqlCompletionItem::label).toList());
    }
    @Test void resolvedDictionaryUsesActualServerCatalogAndKeepsReadChecks() {
        var profile = new CompletionEnvironment("DM8 build-A",Map.of("COMPATIBLE_MODE","2"));
        doReturn(new CompletionFunctionCatalog(List.of(new CompletionFunction("VERSION_FN","VERSION_FN(${1:arg})","VERSION_FN(arg:INTEGER) → VARCHAR",true)),profile,true)).when(dm).functionCatalog(connection);
        var dictionary = service.dictionary(db,true);
        assertEquals(profile,dictionary.environment());
        assertTrue(dictionary.items().stream().anyMatch(i -> i.label().equals("VERSION_FN")));
        assertFalse(dictionary.items().stream().anyMatch(i -> i.label().equals("UPPER")));
        verify(access).assertReadable(1L);
        assertTrue(complete("SELECT VERSION_|").items().stream().anyMatch(i -> i.detail().contains("INTEGER")));
    }
    @Test void proceduralVariablesSurviveOfflineAndUseNearestDeclaration() {
        when(connections.openConnection(db)).thenThrow(new IllegalStateException("offline"));
        var vars = kind("DECLARE v_name VARCHAR(20); BEGIN DECLARE v_name NUMBER; v_inner INTEGER; BEGIN v_| END; END;","VARIABLE");
        assertEquals(2,vars.size());
        assertEquals("NUMBER",vars.stream().filter(i -> i.label().equals("V_NAME")).findFirst().orElseThrow().detail());
        assertTrue(kind("DECLARE v_name VARCHAR(20); BEGIN DECLARE v_inner NUMBER; BEGIN NULL; END; v_i| END;","VARIABLE").isEmpty());
        assertTrue(kind("CREATE PROCEDURE p(p_id IN INTEGER) AS BEGIN SELECT p_| FROM EMPLOYEE; END;","VARIABLE").stream().anyMatch(i -> i.label().equals("P_ID")));
    }
    @Test void proceduralRecordsAndCursorLoopExposeTheirActualFields() {
        assertEquals(4,kind("DECLARE r EMPLOYEE%ROWTYPE; BEGIN SELECT r.| FROM DUAL; END;","COLUMN").size());
        assertEquals(List.of("CODE","NAME"),kind("DECLARE TYPE t IS RECORD(code NUMBER, name VARCHAR(20)); r t; BEGIN r.| END;","COLUMN").stream().map(SqlCompletionItem::label).toList());
        assertEquals(List.of("EMP_ID"),kind("BEGIN FOR r IN (SELECT ID AS EMP_ID FROM EMPLOYEE) LOOP r.| END LOOP; END;","COLUMN").stream().map(SqlCompletionItem::label).toList());
        assertTrue(kind("BEGIN FOR i IN 1..10 LOOP NULL; END LOOP; i| END;","VARIABLE").isEmpty());
    }
    @Test void packageMembersRespectExpressionCallAndSchemaContexts() {
        doReturn(List.of("HR_API")).when(dm).completionPackages(connection,"APP");
        doReturn(List.of(new CompletionRoutine("GET_NAME",List.of(new ParameterInfo("ID","INTEGER")),"VARCHAR",false,"1"),
                new CompletionRoutine("SAVE_EMP",List.of(),null,true,"2"))).when(dm).completionPackageMembers(connection,"APP","HR_API");
        assertEquals("GET_NAME(${1:ID})",kind("SELECT HR_API.| FROM EMPLOYEE","FUNCTION").get(0).insertText());
        assertTrue(kind("SELECT HR_API.| FROM EMPLOYEE","PROCEDURE").isEmpty());
        assertEquals("SAVE_EMP()",kind("CALL APP.HR_API.|","PROCEDURE").get(0).insertText());
        assertEquals("SAVE_EMP()",kind("BEGIN HR_API.| END;","PROCEDURE").get(0).insertText());
        assertEquals("GET_NAME(${1:ID})",kind("DECLARE v VARCHAR(10); BEGIN v := HR_API.| END;","FUNCTION").get(0).insertText());
        for (String prefix : List.of("BEGIN IF 1=1 THEN ", "BEGIN IF 1=1 THEN NULL; ELSE ",
                "BEGIN FOR i IN 1..10 LOOP ", "BEGIN CASE WHEN 1=1 THEN ")) {
            assertEquals(List.of("SAVE_EMP"), kind(prefix + "HR_API.| END;", "PROCEDURE").stream().map(SqlCompletionItem::label).toList());
            assertTrue(kind(prefix + "HR_API.| END;", "FUNCTION").isEmpty());
        }
        for (String prefix : List.of("BEGIN v := CASE WHEN 1=1 THEN ",
                "BEGIN v := CASE WHEN 1=1 THEN NULL ELSE ", "BEGIN SELECT CASE WHEN 1=1 THEN ")) {
            assertEquals(List.of("GET_NAME"), kind(prefix + "HR_API.| END;", "FUNCTION").stream().map(SqlCompletionItem::label).toList());
        }
    }
    @Test void synonymCompletionResolvesChainsAndPrivateShadowsPublic() {
        doReturn(List.of(new CompletionSynonym("APP","STAFF","APP","NEXT_STAFF","","SYNONYM"),
                new CompletionSynonym("APP","NEXT_STAFF","APP","EMPLOYEE","","TABLE"),
                new CompletionSynonym("PUBLIC","STAFF","APP","DEPARTMENT","","TABLE"))).when(dm).completionSynonyms(connection,"APP");
        assertEquals("STAFF",kind("SELECT * FROM STA|","SYNONYM").get(0).insertText());
        assertEquals(4,kind("SELECT s.| FROM STAFF s","COLUMN").size());
        assertTrue(kind("SELECT s.| FROM STAFF s","COLUMN").stream().anyMatch(i -> i.label().equals("NAME")));
    }
    @Test void remoteAndCyclicSynonymsDoNotTriggerUnboundedOrRemoteResolution() {
        doReturn(List.of(new CompletionSynonym("APP","A1","APP","A2","","SYNONYM"),
                new CompletionSynonym("APP","A2","APP","A1","","SYNONYM"),
                new CompletionSynonym("APP","REMOTE_EMP","OTHER","EMPLOYEE","REMOTE_DB","TABLE"))).when(dm).completionSynonyms(connection,"APP");
        assertTrue(kind("SELECT x.| FROM A1 x","COLUMN").isEmpty());
        assertTrue(kind("SELECT x.| FROM REMOTE_EMP x","COLUMN").isEmpty());
        verify(columns,never()).getColumns(connection,null,"OTHER","EMPLOYEE");
    }
    @Test void compoundOrderingUsesTheFirstProjectionAndAliasDefinitionDoesNotOfferFields() {
        var output = kind("SELECT ID AS EMP_ID FROM EMPLOYEE UNION ALL SELECT ID AS DEPT_ID FROM DEPARTMENT ORDER BY |","COLUMN");
        assertEquals(List.of("EMP_ID"),output.stream().map(SqlCompletionItem::label).toList());
        assertTrue(complete("SELECT ID AS | FROM EMPLOYEE").items().isEmpty());
        assertTrue(kind("INSERT INTO EMPLOYEE (ID) VALUES (|)","COLUMN").isEmpty());
    }
    @Test void selectAndFetchIntoUseLocalVariablesRatherThanTableCandidates() {
        String select = "DECLARE v_id INTEGER; BEGIN SELECT ID INTO v_| FROM EMPLOYEE; END;";
        assertEquals(List.of("V_ID"),kind(select,"VARIABLE").stream().map(SqlCompletionItem::label).toList());
        assertTrue(kind(select,"TABLE").isEmpty());
        String fetch = "DECLARE CURSOR c IS SELECT ID FROM EMPLOYEE; v_id INTEGER; BEGIN FETCH c INTO v_|; END;";
        assertEquals(List.of("V_ID"),kind(fetch,"VARIABLE").stream().map(SqlCompletionItem::label).toList());
    }
    @Test void namedCursorsExposeProjectionFieldsInLoopsAndRowtypeRecords() {
        String declarations = "DECLARE CURSOR c IS SELECT ID AS EMP_ID FROM EMPLOYEE; r c%ROWTYPE; BEGIN ";
        assertEquals(List.of("EMP_ID"),kind(declarations + "r.| END;","COLUMN").stream().map(SqlCompletionItem::label).toList());
        assertEquals(List.of("EMP_ID"),kind(declarations + "FOR rec IN c LOOP rec.| END LOOP; END;","COLUMN").stream().map(SqlCompletionItem::label).toList());
    }
    @Test void packageSynonymChainsAndOverloadsKeepQualifiedInsertionAndExactCase() {
        doReturn(List.of("Api")).when(dm).completionPackages(connection,"Mixed");
        doReturn(List.of(new CompletionSynonym("APP","PUBLIC_API","APP","NEXT_API","","SYNONYM"),
                new CompletionSynonym("APP","NEXT_API","Mixed","Api","","PACKAGE"))).when(dm).completionSynonyms(connection,"APP");
        doReturn(List.of(new CompletionRoutine("Find",List.of(new ParameterInfo("ID","INTEGER")),"VARCHAR",false,"1"),
                new CompletionRoutine("Find",List.of(new ParameterInfo("ID","VARCHAR")),"VARCHAR",false,"2")))
                .when(dm).completionPackageMembers(connection,"Mixed","Api");
        var members = kind("SELECT PUBLIC_API.| FROM EMPLOYEE","FUNCTION");
        assertEquals(2,members.size());
        assertEquals("\"Find\"(${1:ID})",members.get(0).insertText());
        assertTrue(members.stream().anyMatch(i -> i.detail().contains("INTEGER")));
        assertEquals(2,kind("SELECT \"Mixed\".\"Api\".| FROM EMPLOYEE","FUNCTION").size());
    }
    @Test void standaloneRoutineSynonymsUseTheirTargetsSignaturesAndRealObjectsShadowPublicAliases() {
        when(functions.getFunctions(connection,null,"APP")).thenReturn(List.of(new FunctionMetadata("GET_NAME",List.of(new ParameterInfo("ID","INTEGER")),"VARCHAR")));
        when(procedures.getProcedures(connection,null,"APP")).thenReturn(List.of(new ProcedureMetadata("SAVE_EMP",List.of())));
        doReturn(List.of(new CompletionSynonym("APP","FIND_NAME","APP","GET_NAME","","FUNCTION"),
                new CompletionSynonym("APP","SAVE_STAFF","APP","SAVE_EMP","","PROCEDURE"),
                new CompletionSynonym("PUBLIC","EMPLOYEE","APP","DEPARTMENT","","TABLE"))).when(dm).completionSynonyms(connection,"APP");
        var function = kind("SELECT FIND_| FROM EMPLOYEE","FUNCTION").get(0);
        assertEquals("FIND_NAME(${1:ID})",function.insertText()); assertTrue(function.detail().contains(" → VARCHAR"));
        assertEquals("SAVE_STAFF()",kind("CALL SAVE_STA|","PROCEDURE").get(0).insertText());
        assertTrue(kind("SELECT * FROM EMP|","SYNONYM").isEmpty());
        assertEquals(4,kind("SELECT e.| FROM EMPLOYEE e","COLUMN").size());
    }

    @Test void routineParametersSurviveEarlierStatementsWithoutLeakingBetweenRoutines() {
        when(connections.openConnection(db)).thenThrow(new IllegalStateException("offline"));
        assertEquals(List.of("P_ID"), kind("SELECT 1 FROM DUAL; CREATE PROCEDURE p(p_id INTEGER) AS BEGIN SELECT p_| FROM DUAL; END;",
                "VARIABLE").stream().map(SqlCompletionItem::label).toList());
        assertEquals(List.of("SECOND_ID"), kind("CREATE PROCEDURE p(first_id INTEGER) AS BEGIN NULL; END; "
                + "CREATE PROCEDURE q(second_id INTEGER) AS BEGIN SELECT | FROM DUAL; END;",
                "VARIABLE").stream().map(SqlCompletionItem::label).toList());
    }

    @Test void synonymTypeAndPrefixFilteringAvoidsUnrelatedSchemaQueries() {
        doReturn(List.of(new CompletionSynonym("APP", "FIND_TABLE", "TABLE_OWNER", "EMPLOYEE", "", "TABLE"),
                new CompletionSynonym("APP", "OTHER_FN", "UNRELATED", "FN", "", "FUNCTION"),
                new CompletionSynonym("APP", "FIND_FN", "REQUESTED", "FN", "", "FUNCTION"),
                new CompletionSynonym("APP", "FIND_SAVE", "PROC_OWNER", "SAVE", "", "PROCEDURE"),
                new CompletionSynonym("APP", "FIND_PKG", "PKG_OWNER", "PKG", "", "PACKAGE")))
                .when(dm).completionSynonyms(connection, "APP");
        when(functions.getFunctions(connection, null, "REQUESTED"))
                .thenReturn(List.of(new FunctionMetadata("FN", List.of(), "INTEGER")));
        assertEquals(List.of("FIND_FN"), kind("SELECT FIND_| FROM EMPLOYEE", "FUNCTION").stream().map(SqlCompletionItem::label).toList());
        verify(functions).getFunctions(connection, null, "REQUESTED");
        for (String owner : List.of("TABLE_OWNER", "UNRELATED", "PROC_OWNER", "PKG_OWNER")) {
            verify(functions, never()).getFunctions(connection, null, owner);
            verify(procedures, never()).getProcedures(connection, null, owner);
        }
        for (String owner : List.of("TABLE_OWNER", "UNRELATED", "PROC_OWNER", "REQUESTED"))
            verify(dm, never()).completionPackages(connection, owner);
    }

    @Test void privateSynonymsShadowPublicBeforeTypeFilteringAndQuotedPrefixesStayExact() {
        doReturn(List.of(new CompletionSynonym("APP", "ALIAS", "HIDDEN", "T", "", "TABLE"),
                new CompletionSynonym("PUBLIC", "ALIAS", "PUBLIC_OWNER", "FN", "", "FUNCTION"),
                new CompletionSynonym("APP", "Find", "MIXED", "FN", "", "FUNCTION")))
                .when(dm).completionSynonyms(connection, "APP");
        when(functions.getFunctions(connection, null, "MIXED"))
                .thenReturn(List.of(new FunctionMetadata("FN", List.of(), "INTEGER")));
        assertTrue(kind("SELECT ALI|", "FUNCTION").isEmpty());
        assertTrue(kind("SELECT \"find|", "FUNCTION").isEmpty());
        verify(functions, never()).getFunctions(connection, null, "PUBLIC_OWNER");
        verify(functions, never()).getFunctions(connection, null, "MIXED");
        assertEquals(List.of("Find"), kind("SELECT \"Fi|", "FUNCTION").stream().map(SqlCompletionItem::label).toList());
    }

    @Test void tableSynonymsDoNotFanOutRoutineQueriesAtAnEmptyExpressionPrefix() {
        var aliases = IntStream.range(0, 50).mapToObj(i -> new CompletionSynonym(
                "APP", "STAFF_" + i, "OWNER_" + i, "EMPLOYEE", "", "TABLE")).toList();
        doReturn(aliases).when(dm).completionSynonyms(connection, "APP");
        complete("SELECT | FROM EMPLOYEE");
        for (int i = 0; i < 50; i++) {
            String owner = "OWNER_" + i;
            verify(functions, never()).getFunctions(connection, null, owner);
            verify(procedures, never()).getProcedures(connection, null, owner);
            verify(dm, never()).completionPackages(connection, owner);
        }
    }

    @Test void overlongSynonymChainDoesNotHideOtherAliasesWithShorterValidPaths() {
        List<CompletionSynonym> aliases = new ArrayList<>();
        for (int i = 0; i < 19; i++) aliases.add(new CompletionSynonym(
                "APP", String.format("A%02d", i), "APP", String.format("A%02d", i + 1), "", "SYNONYM"));
        aliases.add(new CompletionSynonym("APP", "A19", "APP", "FN", "", "FUNCTION"));
        doReturn(aliases).when(dm).completionSynonyms(connection, "APP");
        when(functions.getFunctions(connection, null, "APP"))
                .thenReturn(List.of(new FunctionMetadata("FN", List.of(), "INTEGER")));
        var names = kind("SELECT A|", "FUNCTION").stream().filter(i -> "metadata".equals(i.source()))
                .map(SqlCompletionItem::label).toList();
        assertFalse(names.contains("A00"));
        assertFalse(names.contains("A02"));
        assertTrue(names.contains("A03"));
        assertTrue(names.contains("A19"));
        verify(functions, times(1)).getFunctions(connection, null, "APP");
    }

}
