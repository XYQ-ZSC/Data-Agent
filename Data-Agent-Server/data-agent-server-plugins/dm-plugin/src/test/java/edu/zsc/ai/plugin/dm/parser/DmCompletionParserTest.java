package edu.zsc.ai.plugin.dm.parser;

import edu.zsc.ai.plugin.model.sql.CompletionContext;
import edu.zsc.ai.plugin.model.sql.CompletionScope;
import edu.zsc.ai.plugin.model.sql.CompletionTableRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmCompletionParserTest {
    @Test void routineParametersAreScopedToEachRoutineInAScript() {
        String first = "SELECT 1 FROM DUAL; CREATE PROCEDURE p(p_id IN INTEGER) AS BEGIN SELECT p_";
        assertEquals(List.of("P_ID"), analyze(first).variables().stream().map(v -> v.name().name()).toList());
        String second = "CREATE PROCEDURE first_p(first_id INTEGER) AS BEGIN NULL; END; "
                + "CREATE OR REPLACE FUNCTION APP.\"Second\"(\"secondId\" INTEGER) RETURN INTEGER AS BEGIN SELECT ";
        assertEquals(List.of("secondId"), analyze(second).variables().stream().map(v -> v.name().name()).toList());
        assertTrue(analyze(second + "1; END; SELECT ").variables().isEmpty());
    }

    @Test void proceduralStatementBoundariesRemainDistinctFromCaseExpressions() {
        for (String prefix : List.of("BEGIN IF 1 = 1 THEN ", "BEGIN IF 1 = 1 THEN NULL; ELSE ",
                "BEGIN FOR i IN 1..10 LOOP ", "BEGIN CASE WHEN 1 = 1 THEN ")) {
            assertEquals(CompletionScope.PROCEDURAL, analyze(prefix).scope(), prefix);
            assertTrue(!analyze(prefix + "HR_API.").expressionPosition(), prefix);
        }
        for (String prefix : List.of("BEGIN v := CASE WHEN 1 = 1 THEN ",
                "BEGIN v := CASE WHEN 1 = 1 THEN NULL ELSE ",
                "BEGIN SELECT CASE WHEN 1 = 1 THEN ", "BEGIN RETURN ",
                "BEGIN v := CASE WHEN 1 = 1 THEN CASE WHEN 2 = 2 THEN ")) {
            assertTrue(analyze(prefix + "HR_API.").expressionPosition(), prefix);
        }
    }

    @Test void proceduralBindingsFollowScopeAndDoNotEscapeClosedBlocks() {
        String sql = "DECLARE v NUMBER; BEGIN DECLARE v VARCHAR(10); BEGIN v";
        var context = DmCompletionParser.INSTANCE.analyze(sql,sql.length());
        assertTrue(context.procedural());
        assertEquals("VARCHAR(10)",context.variables().get(0).type());
        sql = "DECLARE v NUMBER; BEGIN DECLARE hidden VARCHAR(10); BEGIN NULL; END; v";
        context = DmCompletionParser.INSTANCE.analyze(sql,sql.length());
        assertEquals(List.of("V"),context.variables().stream().map(v -> v.name().name()).toList());
    }
    @Test void sqlCaseEndDoesNotCloseTheProceduralBlock() {
        String sql="DECLARE v NUMBER; BEGIN SELECT CASE WHEN 1=1 THEN 1 ELSE 2 END INTO v FROM DUAL; v";
        var context=DmCompletionParser.INSTANCE.analyze(sql,sql.length());
        assertTrue(context.procedural()); assertEquals("V",context.variables().get(0).name().name());
    }

    private final DmCompletionParser parser = DmCompletionParser.INSTANCE;

    private CompletionContext analyze(String sql) {
        return parser.analyze(sql, sql.length());
    }

    @Test
    void emptySqlIsKeywordScope() {
        CompletionContext context = analyze("");
        assertEquals(CompletionScope.KEYWORD, context.scope());
        assertEquals(0, context.replaceStart());
        assertEquals(0, context.replaceEnd());
    }

    @Test
    void statementStartAfterSemicolonIsKeywordScope() {
        CompletionContext context = analyze("SELECT 1; ");
        assertEquals(CompletionScope.KEYWORD, context.scope());
    }

    @Test
    void afterFromIsTableScope() {
        CompletionContext context = analyze("SELECT * FROM ");
        assertEquals(CompletionScope.TABLE, context.scope());
    }

    @Test
    void afterJoinIsTableScope() {
        CompletionContext context = analyze("SELECT * FROM EMPLOYEE e JOIN ");
        assertEquals(CompletionScope.JOIN, context.scope());
        assertEquals(CompletionTableRef.of(null, "EMPLOYEE"), context.aliases().get("E"));
    }

    @Test
    void afterInsertIntoIsTableScope() {
        CompletionContext context = analyze("INSERT INTO ");
        assertEquals(CompletionScope.TABLE, context.scope());
    }

    @Test
    void selectListIsColumnScope() {
        CompletionContext context = analyze("SELECT ");
        assertEquals(CompletionScope.COLUMN, context.scope());
    }

    @Test
    void aliasDotResolvesToAliasedTable() {
        String sql = "SELECT t. FROM HR.EMPLOYEE t";
        int cursor = "SELECT t.".length();
        CompletionContext context = parser.analyze(sql, cursor);
        assertEquals(CompletionScope.QUALIFIED, context.scope());
        assertEquals("T", context.qualifier());
        assertNull(context.qualifiedTable());
        assertEquals(CompletionTableRef.of("HR", "EMPLOYEE"), context.aliases().get("T"));
    }

    @Test
    void schemaTableDotResolvesToQualifiedTable() {
        CompletionContext context = analyze("SELECT HR.EMPLOYEE.");
        assertEquals(CompletionScope.QUALIFIED, context.scope());
        assertEquals(CompletionTableRef.of("HR", "EMPLOYEE"), context.qualifiedTable());
    }

    @Test
    void schemaDotKeepsSingleQualifier() {
        CompletionContext context = analyze("SELECT * FROM HR.");
        assertEquals(CompletionScope.QUALIFIED, context.scope());
        assertEquals("HR", context.qualifier());
        assertNull(context.qualifiedTable());
    }

    @Test
    void partialWordDefinesReplaceRange() {
        String sql = "SELECT * FROM EMP";
        CompletionContext context = analyze(sql);
        assertEquals(CompletionScope.TABLE, context.scope());
        assertEquals(sql.length() - 3, context.replaceStart());
        assertEquals(sql.length(), context.replaceEnd());
    }

    @Test
    void midWordCompletionExtendsReplaceEndToWordEnd() {
        String sql = "SELECT * FROM EMPLOYEE";
        int cursor = "SELECT * FROM EMP".length();
        CompletionContext context = parser.analyze(sql, cursor);
        assertEquals("SELECT * FROM ".length(), context.replaceStart());
        assertEquals(sql.length(), context.replaceEnd());
    }

    @Test
    void quotedPrefixIncludesOpeningQuoteInReplaceRange() {
        String sql = "SELECT * FROM \"My";
        CompletionContext context = analyze(sql);
        assertEquals("SELECT * FROM ".length(), context.replaceStart());
        assertEquals(sql.length(), context.replaceEnd());
    }

    @Test
    void quotedPrefixSwallowsClosingQuoteInReplaceEnd() {
        String sql = "SELECT * FROM \"My\" WHERE x = 1";
        int cursor = "SELECT * FROM \"My".length();
        CompletionContext context = parser.analyze(sql, cursor);
        assertEquals("SELECT * FROM ".length(), context.replaceStart());
        // covers the whole quoted identifier, including the closing quote
        assertEquals("SELECT * FROM \"My\"".length(), context.replaceEnd());
    }

    @Test
    void underscoreLeadingNamesAreIdentifiers() {
        CompletionContext context = analyze("SELECT * FROM _emp e WHERE e.");
        assertEquals(CompletionScope.QUALIFIED, context.scope());
        assertEquals(CompletionTableRef.of(null, "_EMP"), context.aliases().get("E"));
    }

    @Test
    void quotedAliasKeepsExactCase() {
        CompletionContext context = analyze("SELECT * FROM EMPLOYEE \"MyAlias\" WHERE \"MyAlias\".");
        assertEquals(CompletionScope.QUALIFIED, context.scope());
        assertEquals("MyAlias", context.qualifier());
        assertEquals(CompletionTableRef.of(null, "EMPLOYEE"), context.aliases().get("MyAlias"));
        // the unquoted spelling must not match the quoted alias
        assertNull(context.aliases().get("MYALIAS"));
    }

    @Test
    void unqualifiedTablesAndAliasesAreCollected() {
        CompletionContext context = analyze("SELECT e. FROM EMPLOYEE, DEPARTMENT d WHERE ");
        assertEquals(CompletionScope.COLUMN, context.scope());
        assertTrue(context.tables().contains(CompletionTableRef.of(null, "EMPLOYEE")));
        assertEquals(CompletionTableRef.of(null, "DEPARTMENT"), context.aliases().get("D"));
    }

    @Test
    void unionBranchesDoNotOverwriteEachOthersAliases() {
        String sql = "SELECT a. FROM T1 a UNION SELECT a. FROM T2 a";
        int cursor = sql.lastIndexOf("a.") + 2;
        CompletionContext context = parser.analyze(sql, cursor);
        assertEquals(CompletionScope.QUALIFIED, context.scope());
        // the second branch's binding wins at the cursor
        assertEquals(CompletionTableRef.of(null, "T2"), context.aliases().get("A"));
    }

    @Test
    void innerQueryAliasesShadowOuterOnes() {
        String sql = "SELECT o. FROM OUTER_T o WHERE EXISTS (SELECT i. FROM INNER_T i WHERE i.x = o.x)";
        int cursor = sql.indexOf("i.x") + 2;
        CompletionContext context = parser.analyze(sql, cursor);
        assertEquals(CompletionScope.QUALIFIED, context.scope());
        assertEquals(CompletionTableRef.of(null, "INNER_T"), context.aliases().get("I"));
        // the outer alias stays visible for correlated references
        assertEquals(CompletionTableRef.of(null, "OUTER_T"), context.aliases().get("O"));
    }

    @Test
    void cteNamesAndColumnsAreCollected() {
        CompletionContext context = analyze(
                "WITH active AS (SELECT id, name FROM EMPLOYEE), recent AS (SELECT * FROM ORDERS) SELECT * FROM ");
        assertEquals(CompletionScope.TABLE, context.scope());
        assertTrue(context.ctes().contains("ACTIVE"));
        assertTrue(context.ctes().contains("RECENT"));
        assertEquals(List.of("ID", "NAME"), context.cteColumns().get("ACTIVE"));
    }

    @Test
    void cteExplicitColumnListWins() {
        CompletionContext context = analyze(
                "WITH c(a, b) AS (SELECT x, y FROM T) SELECT c. FROM c");
        assertEquals(List.of("A", "B"), context.cteColumns().get("C"));
    }

    @Test
    void derivedTableColumnsAreParsedFromSelectList() {
        String sql = "SELECT d. FROM (SELECT id, total amt, t.code FROM ORDERS t) d WHERE d.id > 0";
        int cursor = "SELECT d.".length();
        CompletionContext context = parser.analyze(sql, cursor);
        assertEquals(CompletionScope.QUALIFIED, context.scope());
        assertEquals(List.of("ID", "AMT", "CODE"), context.derivedTables().get("D"));
    }

    @Test
    void cursorInStringLiteralYieldsNoContext() {
        assertEquals(CompletionScope.NONE, analyze("SELECT * FROM T WHERE name = 'abc").scope());
        assertEquals(CompletionScope.NONE,
                parser.analyze("SELECT 'a' || '", "SELECT 'a' || '".length()).scope());
    }

    @Test
    void cursorInCommentYieldsNoContext() {
        assertEquals(CompletionScope.NONE, analyze("SELECT /* comment ").scope());
        assertEquals(CompletionScope.NONE, analyze("SELECT 1 -- trailing").scope());
    }

    @Test
    void cursorAfterCommentResumesNormalScope() {
        assertEquals(CompletionScope.COLUMN, analyze("SELECT /* done */ ").scope());
    }

    @Test
    void incompleteSqlNeverFails() {
        String sql = "SELCT t. FORM WHERE (((";
        CompletionContext context = parser.analyze(sql, "SELCT t.".length());
        assertEquals(CompletionScope.QUALIFIED, context.scope());
        assertEquals("T", context.qualifier());
    }

    @Test
    void cursorOffsetIsClamped() {
        CompletionContext context = DmCompletionParser.INSTANCE.analyze("SELECT ", 9999);
        assertEquals(CompletionScope.COLUMN, context.scope());
        assertEquals("SELECT ".length(), context.replaceEnd());
    }
    @Test
    void specialInitialsAndUnicodeIdentifiersKeepExactTableBindings() {
        for (String name : List.of("_EMPLOYEE", "$EMPLOYEE", "#EMPLOYEE", "员工表")) {
            String sql = "SELECT t. FROM " + name + " t";
            var context = parser.analyze(sql, "SELECT t.".length());
            assertEquals(name, context.relations().get(0).table().name());
        }
    }

    @Test
    void quotedPrefixDecodesEscapesAndReplacesCompleteSpacedToken() {
        String sql = "SELECT t.\"a\"\"b Old\" FROM T t";
        int cursor = sql.indexOf(" Old");
        var context = parser.analyze(sql, cursor);
        assertEquals("a\"b", context.prefix());
        assertTrue(context.quotedPrefix());
        assertEquals("\"a\"\"b Old\"", sql.substring(context.replaceStart(), context.replaceEnd()));
    }

    @Test
    void setAndInsertColumnsCarryExclusions() {
        assertEquals(CompletionScope.UPDATE_COLUMN, analyze("UPDATE T SET ID=1, ").scope());
        assertEquals(List.of("ID"), analyze("UPDATE T SET ID=1, ").excludedColumns());
        String sql = "INSERT INTO T (ID, )";
        var context = parser.analyze(sql, sql.length()-1);
        assertEquals(CompletionScope.INSERT_COLUMN, context.scope());
        assertEquals(List.of("ID"), context.excludedColumns());
    }

    @Test
    void alternativeLiteralAndCrLineCommentAreHandled() {
        assertEquals(CompletionScope.NONE, analyze("SELECT q'[abc").scope());
        assertEquals(CompletionScope.COLUMN, analyze("SELECT -- done\r ").scope());
    }

    @Test
    void malformedDeepParenthesesAreBounded() {
        String sql = "SELECT " + "(".repeat(2000) + "x" + ")".repeat(2000);
        parser.analyze(sql, sql.indexOf('x'));
    }

}
