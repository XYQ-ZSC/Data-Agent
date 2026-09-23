package edu.zsc.ai.plugin.dm.support;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DmMetadataSupportTest {

    @Test
    void preservesCaseSensitiveSchemaName() {
        assertEquals("CaseSensitive", new DmMetadataSupport().resolveSchema(mock(Connection.class), "CaseSensitive"));
    }

    @Test
    void preservesCaseSensitiveObjectNameForDdlLookup() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("ddl sql")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("CREATE TABLE \"MixedCase\" (ID INT)");

        new DmMetadataSupport().getObjectDdl(connection, "CaseSensitive", "MixedCase", "ddl sql", "table");

        verify(statement).setString(1, "MixedCase");
        verify(statement).setString(2, "CaseSensitive");
    }
}
