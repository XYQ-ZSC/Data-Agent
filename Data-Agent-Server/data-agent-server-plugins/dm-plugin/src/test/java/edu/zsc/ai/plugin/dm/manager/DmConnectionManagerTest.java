package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.driver.DriverLoader;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DmConnectionManagerTest {

    @Test
    void closesConnectionWhenSchemaInitializationFails() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("schema denied"));

        ConnectionConfig config = new ConnectionConfig();
        config.setHost("localhost");
        config.setPort(5236);
        config.setSchema("NO_ACCESS");
        config.setDriverJarPath("unused.jar");
        DmConnectionManager manager = new DmConnectionManager(
                () -> "dm.jdbc.driver.DmDriver", () -> "jdbc:dm://%s:%d/%s", () -> 5236);

        try (MockedStatic<DriverLoader> loader = mockStatic(DriverLoader.class);
             MockedStatic<DriverManager> driverManager = mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(anyString(), any(Properties.class)))
                    .thenReturn(connection);

            assertThrows(RuntimeException.class, () -> manager.connect(config));
        }

        verify(connection).close();
    }
}
