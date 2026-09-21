package edu.zsc.ai.plugin.mysql;

import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.db.TableRowValue;
import edu.zsc.ai.plugin.mysql.support.MysqlRowWriteSupport;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Mysql8PluginRowWriteTest {

    private final Mysql8Plugin plugin = new Mysql8Plugin();

    @Test
    void insertRow_buildsPreparedInsertAndReturnsAffectedRows() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        List<TableRowValue> values = List.of(
                new TableRowValue("id", 7L),
                new TableRowValue("name", "alice")
        );

        SqlCommandResult result = plugin.insertRow(connection, "analytics", null, "users", values);

        verify(connection).prepareStatement("INSERT INTO analytics.users (id, name) VALUES (?, ?)");
        verify(statement).setObject(1, 7L);
        verify(statement).setObject(2, "alice");
        assertTrue(result.isSuccess());
        assertFalse(result.isQuery());
        assertEquals(1, result.getAffectedRows());
    }

    @Test
    void deleteRow_rejectsAmbiguousMatch() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(countStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(2L);

        List<TableRowValue> matchValues = List.of(new TableRowValue("name", "alice"));

        SqlCommandResult result = plugin.deleteRow(connection, "analytics", null, "users", matchValues, false);

        verify(connection).prepareStatement("SELECT COUNT(*) AS total FROM analytics.users WHERE name = ?");
        verify(countStatement).setObject(1, "alice");
        verify(countStatement, never()).executeUpdate();
        assertFalse(result.isSuccess());
        assertEquals("Delete target is ambiguous: matched 2 rows. Retry with force=true to continue.", result.getErrorMessage());
        assertEquals(2, result.getAffectedRows());
        assertEquals(MysqlRowWriteSupport.DELETE_REQUIRES_FORCE_CODE, result.getMessages().get(0).getCode());
    }

    @Test
    void deleteRow_executesPreparedDeleteForSingleMatch() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        PreparedStatement deleteStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT COUNT(*) AS total FROM analytics.users WHERE id = ?"))
                .thenReturn(countStatement);
        when(connection.prepareStatement("DELETE FROM analytics.users WHERE id = ?"))
                .thenReturn(deleteStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(1L);
        when(deleteStatement.executeUpdate()).thenReturn(1);

        List<TableRowValue> matchValues = List.of(new TableRowValue("id", 7L));

        SqlCommandResult result = plugin.deleteRow(connection, "analytics", null, "users", matchValues, false);

        verify(countStatement).setObject(1, 7L);
        verify(deleteStatement).setObject(1, 7L);
        assertTrue(result.isSuccess());
        assertFalse(result.isQuery());
        assertEquals(1, result.getAffectedRows());
    }

    @Test
    void deleteRow_handlesNullMatchValue() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        PreparedStatement deleteStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT COUNT(*) AS total FROM analytics.users WHERE name IS NULL"))
                .thenReturn(countStatement);
        when(connection.prepareStatement("DELETE FROM analytics.users WHERE name IS NULL"))
                .thenReturn(deleteStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(1L);
        when(deleteStatement.executeUpdate()).thenReturn(1);

        List<TableRowValue> matchValues = List.of(new TableRowValue("name", null));

        SqlCommandResult result = plugin.deleteRow(connection, "analytics", null, "users", matchValues, false);

        verify(countStatement, never()).setObject(anyInt(), any());
        verify(deleteStatement, never()).setObject(anyInt(), any());
        assertTrue(result.isSuccess());
        assertFalse(result.isQuery());
        assertEquals(1, result.getAffectedRows());
    }

    @Test
    void deleteRow_executesPreparedDeleteForMultiMatchWhenForced() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        PreparedStatement deleteStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT COUNT(*) AS total FROM analytics.users WHERE name = ?"))
                .thenReturn(countStatement);
        when(connection.prepareStatement("DELETE FROM analytics.users WHERE name = ?"))
                .thenReturn(deleteStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(2L);
        when(deleteStatement.executeUpdate()).thenReturn(2);

        List<TableRowValue> matchValues = List.of(new TableRowValue("name", "alice"));

        SqlCommandResult result = plugin.deleteRow(connection, "analytics", null, "users", matchValues, true);

        verify(countStatement).setObject(1, "alice");
        verify(deleteStatement).setObject(1, "alice");
        assertTrue(result.isSuccess());
        assertFalse(result.isQuery());
        assertEquals(2, result.getAffectedRows());
    }

    @Test
    void updateRow_executesPreparedUpdateForSingleMatch() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        PreparedStatement updateStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT COUNT(*) AS total FROM analytics.users WHERE id = ?"))
                .thenReturn(countStatement);
        when(connection.prepareStatement("UPDATE analytics.users SET name = ? WHERE id = ?"))
                .thenReturn(updateStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(1L);
        when(updateStatement.executeUpdate()).thenReturn(1);

        List<TableRowValue> setValues = List.of(new TableRowValue("name", "bob"));
        List<TableRowValue> matchValues = List.of(new TableRowValue("id", 7L));

        SqlCommandResult result = plugin.updateRow(connection, "analytics", null, "users",
                setValues, matchValues, false);

        verify(countStatement).setObject(1, 7L);
        verify(updateStatement).setObject(1, "bob");
        verify(updateStatement).setObject(2, 7L);
        assertTrue(result.isSuccess());
        assertFalse(result.isQuery());
        assertEquals(1, result.getAffectedRows());
    }

    @Test
    void updateRow_rejectsAmbiguousMatch() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(countStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(2L);

        List<TableRowValue> setValues = List.of(new TableRowValue("name", "bob"));
        List<TableRowValue> matchValues = List.of(new TableRowValue("name", "alice"));

        SqlCommandResult result = plugin.updateRow(connection, "analytics", null, "users",
                setValues, matchValues, false);

        verify(connection).prepareStatement("SELECT COUNT(*) AS total FROM analytics.users WHERE name = ?");
        verify(countStatement, never()).executeUpdate();
        assertFalse(result.isSuccess());
        assertEquals("Update target is ambiguous: matched 2 rows. Retry with force=true to continue.", result.getErrorMessage());
        assertEquals(2, result.getAffectedRows());
        assertEquals(MysqlRowWriteSupport.UPDATE_REQUIRES_FORCE_CODE, result.getMessages().get(0).getCode());
    }

    @Test
    void updateRow_executesPreparedUpdateForMultiMatchWhenForced() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        PreparedStatement updateStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT COUNT(*) AS total FROM analytics.users WHERE name = ?"))
                .thenReturn(countStatement);
        when(connection.prepareStatement("UPDATE analytics.users SET status = ? WHERE name = ?"))
                .thenReturn(updateStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(2L);
        when(updateStatement.executeUpdate()).thenReturn(2);

        List<TableRowValue> setValues = List.of(new TableRowValue("status", 1L));
        List<TableRowValue> matchValues = List.of(new TableRowValue("name", "alice"));

        SqlCommandResult result = plugin.updateRow(connection, "analytics", null, "users",
                setValues, matchValues, true);

        verify(updateStatement).setObject(1, 1L);
        verify(updateStatement).setObject(2, "alice");
        assertTrue(result.isSuccess());
        assertEquals(2, result.getAffectedRows());
    }

    @Test
    void updateRow_failsWhenNoRowsMatched() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement countStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(countStatement);
        when(countStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong("total")).thenReturn(0L);

        List<TableRowValue> setValues = List.of(new TableRowValue("name", "bob"));
        List<TableRowValue> matchValues = List.of(new TableRowValue("id", 7L));

        SqlCommandResult result = plugin.updateRow(connection, "analytics", null, "users",
                setValues, matchValues, false);

        verify(countStatement, never()).executeUpdate();
        assertFalse(result.isSuccess());
        assertEquals("No rows matched the selected row", result.getErrorMessage());
    }
}
