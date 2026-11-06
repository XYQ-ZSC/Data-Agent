package edu.zsc.ai.plugin.dm.support;

import edu.zsc.ai.plugin.model.sql.*;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DmCompletionMetadataTest {
    final DmObjectQuerySupport support = mock(DmObjectQuerySupport.class);
    final Connection connection = mock(Connection.class);
    final DmCompletionMetadata metadata = new DmCompletionMetadata(support);
    @Test void actualVersionCatalogUsesOrderedTypedArgumentsAndReturnValues() throws Exception {
        var jdbc = mock(DatabaseMetaData.class); when(connection.getMetaData()).thenReturn(jdbc);
        when(jdbc.getDatabaseProductVersion()).thenReturn("DM8 build-2026");
        when(support.query(connection,DmCompletionMetadata.SETTINGS)).thenReturn(List.of(Map.of("PARA_NAME","COMPATIBLE_MODE","PARA_VALUE","2")));
        when(support.query(connection,DmCompletionMetadata.FUNCTIONS)).thenReturn(List.of(
                Map.of("FUNCTION_ID",1,"FUNCTION_NAME","BUILD_FN","ARG_NUM",2,"SEQ",1,"ARGUMENT_NAME","TEXT","DATA_TYPE","VARCHAR"),
                Map.of("FUNCTION_ID",1,"FUNCTION_NAME","BUILD_FN","ARG_NUM",2,"SEQ",-1,"DATA_TYPE","BIGINT"),
                Map.of("FUNCTION_ID",1,"FUNCTION_NAME","BUILD_FN","ARG_NUM",2,"SEQ",0,"ARGUMENT_NAME","ID","DATA_TYPE","INTEGER")));
        var catalog = metadata.functions(connection);
        assertTrue(catalog.resolved()); assertEquals("DM8 build-2026",catalog.environment().databaseVersion());
        assertEquals("2",catalog.environment().settings().get("COMPATIBLE_MODE"));
        var function = catalog.functions().stream().filter(f -> f.name().equals("BUILD_FN")).findFirst().orElseThrow();
        assertEquals("BUILD_FN(${1:ID}, ${2:TEXT})",function.insertText());
        assertEquals("BUILD_FN(ID:INTEGER, TEXT:VARCHAR) → BIGINT",function.signature());
        assertFalse(catalog.functions().stream().anyMatch(f -> f.name().equals("LOWER")));
        assertTrue(catalog.functions().stream().anyMatch(f -> f.name().equals("CAST") && f.insertText().contains(" AS ")));
    }
    @Test void charsetConversionUsesTheServersEffectiveSetting() {
        when(support.query(connection,DmCompletionMetadata.SETTINGS)).thenReturn(List.of(Map.of("PARA_NAME","ENABLE_CS_CVT","PARA_VALUE","1")));
        var convert = metadata.functions(connection).functions().stream().filter(f -> f.name().equals("CONVERT")).findFirst().orElseThrow();
        assertEquals("CONVERT(${1:text}, ${2:destination_charset})",convert.insertText());
        assertTrue(convert.signature().contains("destination_charset:VARCHAR"));
    }
    @Test void lowPrivilegeAccountsRetainTypedOfflineSignatures() {
        when(support.query(connection,DmCompletionMetadata.SETTINGS)).thenThrow(new IllegalStateException("permission denied"));
        when(support.query(connection,DmCompletionMetadata.FUNCTIONS)).thenThrow(new IllegalStateException("permission denied"));
        var catalog = metadata.functions(connection);
        assertFalse(catalog.resolved());
        assertEquals(116,catalog.functions().size());
        assertTrue(catalog.functions().stream().allMatch(f -> f.signature().contains(" → ")));
    }
    @Test void oracleDateAndCountSignaturesFollowEffectiveSessionParameters() {
        when(support.query(connection,DmCompletionMetadata.SETTINGS)).thenReturn(List.of(
                Map.of("PARA_NAME","COMPATIBLE_MODE","PARA_VALUE","2"),
                Map.of("PARA_NAME","ORA_DATE_FMT","PARA_VALUE","0"),
                Map.of("PARA_NAME","COUNT_64BIT","PARA_VALUE","0")));
        var functions = metadata.functions(connection).functions();
        assertTrue(functions.stream().filter(f -> f.name().equals("CURRENT_DATE")).findFirst().orElseThrow().signature().endsWith(" → TIMESTAMP"));
        assertTrue(functions.stream().filter(f -> f.name().equals("TO_DATE")).findFirst().orElseThrow().signature().endsWith(" → TIMESTAMP"));
        assertTrue(functions.stream().filter(f -> f.name().equals("COUNT")).findFirst().orElseThrow().signature().endsWith(" → INTEGER"));
        when(support.query(connection,DmCompletionMetadata.SETTINGS)).thenReturn(List.of(
                Map.of("PARA_NAME","COMPATIBLE_MODE","PARA_VALUE","2"),Map.of("PARA_NAME","ORA_DATE_FMT","PARA_VALUE","1")));
        assertTrue(metadata.functions(connection).functions().stream().filter(f -> f.name().equals("TO_DATE")).findFirst().orElseThrow().signature().endsWith(" → DATE"));
    }
    @Test void packageOverloadsAreKeptSeparateAndReturnRowsAreNotInsertedAsArguments() {
        when(support.resolveSchema(connection,"Mixed")).thenReturn("Mixed");
        when(support.query(connection,DmCompletionMetadata.MEMBERS,"Mixed","Api")).thenReturn(List.of(
                Map.of("PROCEDURE_NAME","Find","SUBPROGRAM_ID",1),Map.of("PROCEDURE_NAME","Find","SUBPROGRAM_ID",2),
                Map.of("PROCEDURE_NAME","Save","SUBPROGRAM_ID",3)));
        when(support.query(connection,DmCompletionMetadata.ARGUMENTS,"Mixed","Api")).thenReturn(List.of(
                Map.of("OBJECT_NAME","Find","SUBPROGRAM_ID",1,"POSITION",1,"ARGUMENT_NAME","ID","DATA_TYPE","INTEGER","IN_OUT","IN"),
                Map.of("OBJECT_NAME","Find","SUBPROGRAM_ID",1,"POSITION",0,"DATA_TYPE","VARCHAR"),
                Map.of("OBJECT_NAME","Find","SUBPROGRAM_ID",2,"POSITION",0,"DATA_TYPE","VARCHAR"),
                Map.of("OBJECT_NAME","Find","SUBPROGRAM_ID",2,"POSITION",1,"ARGUMENT_NAME","ID","DATA_TYPE","VARCHAR","IN_OUT","IN")));
        var members = metadata.members(connection,"Mixed","Api");
        assertEquals(3,members.size());
        assertFalse(members.get(0).procedure()); assertEquals("VARCHAR",members.get(0).returnType());
        assertEquals("INTEGER IN",members.get(0).parameters().get(0).dataType());
        assertEquals("VARCHAR IN",members.get(1).parameters().get(0).dataType());
        assertTrue(members.get(2).procedure()); assertTrue(members.get(2).parameters().isEmpty());
        verify(support).query(connection,DmCompletionMetadata.MEMBERS,"Mixed","Api");
    }
    @Test void synonymsKeepExactCaseAndDatabaseLinkInformation() {
        when(support.resolveSchema(connection,"Mixed")).thenReturn("Mixed");
        when(support.query(connection,DmCompletionMetadata.SYNONYMS,"Mixed")).thenReturn(List.of(Map.of(
                "OWNER","Mixed","SYNONYM_NAME","my alias","TABLE_OWNER","Other","TABLE_NAME","My Table","DB_LINK","remote","TARGET_TYPE","TABLE")));
        var synonym = metadata.synonyms(connection,"Mixed").get(0);
        assertEquals("my alias",synonym.name()); assertEquals("Other",synonym.targetSchema());
        assertEquals("My Table",synonym.targetName()); assertEquals("remote",synonym.databaseLink());
    }
}
