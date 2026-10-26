package edu.zsc.ai.domain.service.db.impl;

import com.zaxxer.hikari.HikariDataSource;
import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.config.db.ConnectionPoolProperties;
import edu.zsc.ai.domain.service.db.ManagedDataSourceFactory;
import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.dm.Dm8Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies actual DM plugin session restoration when Hikari reuses a physical connection. */
@EnabledIfSystemProperty(named = "dm.live", matches = "true")
class DmPooledConnectionReturnLiveTest {
    private static final String POOL_SCHEMA = "SYSDBA";
    private static final String DRIFT_SCHEMA = "SYS";
    private final Dm8Plugin plugin = new Dm8Plugin();
    private HikariDataSource dataSource;

    @BeforeAll
    static void requireCredentials() {
        Assumptions.assumeTrue(setting("password") != null, "DM live credentials not supplied");
    }

    @AfterEach
    void closePool() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void returningConnectionRestoresSchemaOnSamePhysicalSession() throws Exception {
        ActiveConnectionRegistry.ActiveConnection active = createActive(1);
        Connection physical;
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            assertEquals(POOL_SCHEMA, borrowed.connection().getSchema().toUpperCase());
            physical = borrowed.connection().unwrap(Connection.class);
            setSchema(borrowed.connection(), DRIFT_SCHEMA);
            assertEquals(DRIFT_SCHEMA, borrowed.connection().getSchema().toUpperCase());
        }
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            assertSame(physical, borrowed.connection().unwrap(Connection.class));
            assertEquals(POOL_SCHEMA, borrowed.connection().getSchema().toUpperCase());
        }
    }

    @Test
    void concurrentBorrowAndReturnDoesNotLeakSchemaOrConnections() throws Exception {
        ActiveConnectionRegistry.ActiveConnection active = createActive(3);
        int workers = 6;
        int iterations = 20;
        var executor = Executors.newFixedThreadPool(workers);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < iterations; i++) {
                            try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
                                assertEquals(POOL_SCHEMA, borrowed.connection().getSchema().toUpperCase());
                                setSchema(borrowed.connection(), DRIFT_SCHEMA);
                            }
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(120, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("Pooled DM session leaked state", failure.get());
            assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections());
            assertTrue(dataSource.getHikariPoolMXBean().getTotalConnections() <= 3);
        } finally {
            executor.shutdownNow();
        }
    }

    private ActiveConnectionRegistry.ActiveConnection createActive(int maximumPoolSize) {
        ConnectionPoolProperties properties = new ConnectionPoolProperties();
        properties.setMaximumPoolSize(maximumPoolSize);
        properties.setMinimumIdle(0);
        dataSource = (HikariDataSource) new HikariManagedDataSourceFactory(properties).create(plugin, config(),
                new ManagedDataSourceFactory.ManagedDataSourceRequest(9001L, "dm", null, POOL_SCHEMA));
        return new ActiveConnectionRegistry.ActiveConnection(dataSource, 1L, 1L, 9001L, "dm",
                plugin.getPluginId(), null, POOL_SCHEMA, LocalDateTime.now(), LocalDateTime.now(),
                WorkspaceTypeEnum.PERSONAL, null);
    }

    private static ConnectionConfig config() {
        ConnectionConfig config = new ConnectionConfig();
        config.setHost(setting("host") == null ? "127.0.0.1" : setting("host"));
        config.setPort(setting("port") == null ? 25236 : Integer.parseInt(setting("port")));
        config.setUsername(setting("user") == null ? "SYSDBA" : setting("user"));
        config.setPassword(setting("password"));
        config.setSchema(POOL_SCHEMA);
        config.setDriverJarPath(System.getProperty("dm.driver.jar",
                System.getProperty("user.home") + "/.data-agent/drivers/dm/DmJdbcDriver18-8.1.3.140.jar"));
        return config;
    }

    private static String setting(String key) {
        String property = System.getProperty("dm." + key);
        if (property != null && !property.isBlank()) return property;
        String environment = System.getenv("DM_" + key.toUpperCase());
        return environment == null || environment.isBlank() ? null : environment;
    }

    private static void setSchema(Connection connection, String schema) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA \"" + schema + "\"");
        }
    }
}
