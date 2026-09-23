package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.capability.ConnectionManager;
import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.connection.JdbcConnectionBuilder;
import edu.zsc.ai.plugin.dm.util.DmJdbcConnectionBuilder;
import edu.zsc.ai.plugin.driver.DriverLoader;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class DmConnectionManager implements ConnectionManager {

    private static final Logger logger = Logger.getLogger(DmConnectionManager.class.getName());

    private final JdbcConnectionBuilder connectionBuilder = new DmJdbcConnectionBuilder();
    private final Supplier<String> driverClassNameSupplier;
    private final Supplier<String> jdbcUrlTemplateSupplier;
    private final IntSupplier defaultPortSupplier;

    public DmConnectionManager(Supplier<String> driverClassNameSupplier,
                               Supplier<String> jdbcUrlTemplateSupplier,
                               IntSupplier defaultPortSupplier) {
        this.driverClassNameSupplier = driverClassNameSupplier;
        this.jdbcUrlTemplateSupplier = jdbcUrlTemplateSupplier;
        this.defaultPortSupplier = defaultPortSupplier;
    }

    @Override
    public Connection connect(ConnectionConfig config) {
        Connection connection = null;
        try {
            DriverLoader.loadDriver(config, driverClassNameSupplier.get());

            String jdbcUrl = connectionBuilder.buildUrl(
                    config,
                    jdbcUrlTemplateSupplier.get(),
                    defaultPortSupplier.getAsInt()
            );
            Properties properties = connectionBuilder.buildProperties(config);

            connection = DriverManager.getConnection(jdbcUrl, properties);
            applyCurrentSchema(connection, config);
            logger.info(String.format(
                    "Successfully connected to DM database at %s:%d/%s",
                    config.getHost(),
                    config.getPort() != null ? config.getPort() : defaultPortSupplier.getAsInt(),
                    config.getDatabase() != null ? config.getDatabase() : ""
            ));
            return connection;
        } catch (SQLException e) {
            closeAfterInitializationFailure(connection, e);
            String errorMessage = String.format(
                    "Failed to connect to DM database at %s:%d/%s: %s",
                    config.getHost(),
                    config.getPort() != null ? config.getPort() : defaultPortSupplier.getAsInt(),
                    config.getDatabase() != null ? config.getDatabase() : "",
                    e.getMessage()
            );
            logger.severe(errorMessage);
            throw new RuntimeException(errorMessage, e);
        } catch (RuntimeException e) {
            closeAfterInitializationFailure(connection, e);
            throw e;
        } catch (Exception e) {
            closeAfterInitializationFailure(connection, e);
            String errorMessage = String.format(
                    "Unexpected error while connecting to DM database: %s",
                    e.getMessage()
            );
            logger.severe(errorMessage);
            throw new RuntimeException(errorMessage, e);
        }
    }

    private void closeAfterInitializationFailure(Connection connection, Exception original) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException closeException) {
            original.addSuppressed(closeException);
        }
    }

    /**
     * DM equivalent of "use database": connections are pooled per (catalog, schema),
     * so a schema-scoped console must land on the right current schema.
     */
    private void applyCurrentSchema(Connection connection, ConnectionConfig config) throws SQLException {
        String schema = config.getSchema();
        if (schema == null || schema.isBlank()) {
            return;
        }
        String quoted = "\"" + schema.replace("\"", "\"\"") + "\"";
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA " + quoted);
        }
    }

    @Override
    public boolean testConnection(ConnectionConfig config) {
        try {
            Connection connection = connect(config);
            if (connection != null && !connection.isClosed()) {
                closeConnection(connection);
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.warning(String.format("Connection test failed: %s", e.getMessage()));
            return false;
        }
    }

    @Override
    public void closeConnection(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close database connection: " + e.getMessage(), e);
        }
    }
}
