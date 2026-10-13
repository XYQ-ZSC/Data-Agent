package edu.zsc.ai.plugin.mysql;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test MySQL 8 plugin connection functionality.
 */
@EnabledIfSystemProperty(named = "mysql.live", matches = "true")
public class Mysql8PluginConnectionTest {

    private static final String DRIVER_JAR_PATH = setting("driver.jar");
    private static final String HOST = setting("host") == null ? "localhost" : setting("host");
    private static final int PORT = setting("port") == null ? 3306 : Integer.parseInt(setting("port"));
    private static final String USERNAME = setting("user");
    private static final String PASSWORD = setting("password");

    @BeforeAll
    static void requireLiveConfiguration() {
        Assumptions.assumeTrue(DRIVER_JAR_PATH != null && USERNAME != null && PASSWORD != null,
                "Set mysql.driver.jar, mysql.user and mysql.password for live MySQL tests");
    }

    private static String setting(String name) {
        String property = System.getProperty("mysql." + name);
        if (property != null && !property.isBlank()) return property;
        String environment = System.getenv("MYSQL_" + name.toUpperCase().replace('.', '_'));
        return environment == null || environment.isBlank() ? null : environment;
    }

    @Test
    public void testConnect() throws Exception {
        Mysql8Plugin plugin = new Mysql8Plugin();

        ConnectionConfig config = new ConnectionConfig();
        config.setHost(HOST);
        config.setPort(PORT);
        config.setUsername(USERNAME);
        config.setPassword(PASSWORD);
        config.setDriverJarPath(DRIVER_JAR_PATH);

        Connection connection = null;
        try {
            connection = plugin.connect(config);
            assertNotNull(connection, "Connection should not be null");
            assertFalse(connection.isClosed(), "Connection should be open");

            // Test query
            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT 1 as test");
            assertTrue(rs.next(), "Should have result");
            assertEquals(1, rs.getInt("test"), "Result should be 1");

            System.out.println("✅ MySQL 8 connection test successful!");

        } finally {
            if (connection != null) {
                plugin.closeConnection(connection);
            }
        }
    }

    @Test
    public void testConnectWithDatabase() throws Exception {
        Mysql8Plugin plugin = new Mysql8Plugin();

        ConnectionConfig config = new ConnectionConfig();
        config.setHost(HOST);
        config.setPort(PORT);
        config.setDatabase("mysql");
        config.setUsername(USERNAME);
        config.setPassword(PASSWORD);
        config.setDriverJarPath(DRIVER_JAR_PATH);

        Connection connection = null;
        try {
            connection = plugin.connect(config);
            assertNotNull(connection, "Connection should not be null");

            // Verify we're connected to the mysql database
            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT DATABASE() as db_name");
            assertTrue(rs.next(), "Should have result");
            assertEquals("mysql", rs.getString("db_name"), "Should be connected to mysql database");

            System.out.println("✅ MySQL 8 connection with database test successful!");

        } finally {
            if (connection != null) {
                plugin.closeConnection(connection);
            }
        }
    }

    @Test
    public void testConnectionWithProperties() throws Exception {
        Mysql8Plugin plugin = new Mysql8Plugin();

        // Build properties map
        ConnectionConfig config = new ConnectionConfig();
        config.setHost(HOST);
        config.setPort(PORT);
        config.setUsername(USERNAME);
        config.setPassword(PASSWORD);
        config.setDriverJarPath(DRIVER_JAR_PATH);
        
        // Add custom properties
        config.addProperty("useSSL", "false");
        config.addProperty("allowPublicKeyRetrieval", "true");
        config.addProperty("serverTimezone", "Asia/Shanghai");

        Connection connection = null;
        try {
            connection = plugin.connect(config);
            assertNotNull(connection, "Connection should not be null");
            assertFalse(connection.isClosed(), "Connection should be open");

            System.out.println("✅ MySQL 8 connection with properties test successful!");

        } finally {
            if (connection != null) {
                plugin.closeConnection(connection);
            }
        }
    }

    @Test
    public void testTestConnection() {
        Mysql8Plugin plugin = new Mysql8Plugin();

        ConnectionConfig config = new ConnectionConfig();
        config.setHost(HOST);
        config.setPort(PORT);
        config.setUsername(USERNAME);
        config.setPassword(PASSWORD);
        config.setDriverJarPath(DRIVER_JAR_PATH);

        boolean result = plugin.testConnection(config);
        assertTrue(result, "Connection test should succeed");

        System.out.println("✅ MySQL 8 testConnection() test successful!");
    }

    @Test
    public void testTestConnectionFailure() {
        Mysql8Plugin plugin = new Mysql8Plugin();

        ConnectionConfig config = new ConnectionConfig();
        config.setHost(HOST);
        config.setPort(PORT);
        config.setUsername(USERNAME);
        config.setPassword("wrong_password");
        config.setDriverJarPath(DRIVER_JAR_PATH);

        boolean result = plugin.testConnection(config);
        assertFalse(result, "Connection test should fail with wrong password");

        System.out.println("✅ MySQL 8 testConnection() failure test successful!");
    }
}
