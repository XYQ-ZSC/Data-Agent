package edu.zsc.ai.plugin.driver;

import edu.zsc.ai.plugin.connection.ConnectionConfig;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.*;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

/**
 * Utility class for loading JDBC drivers.
 * Handles loading drivers from external JAR files.
 */
public final class DriverLoader {

    /**
     * Cache for dynamically loaded driver class loaders
     */
    private static final ConcurrentMap<String, URLClassLoader> DRIVER_LOADERS = new ConcurrentHashMap<>();

    /**
     * Private constructor to prevent instantiation.
     */
    private DriverLoader() {
        // Utility class
    }

    /**
     * Load JDBC driver from external JAR file.
     *
     * @param config connection configuration (must contain driverJarPath)
     * @param driverClassName JDBC driver class name
     * @throws RuntimeException if driver loading fails
     */
    public static void loadDriver(ConnectionConfig config, String driverClassName) {
        loadDriverFromJar(config.getDriverJarPath(), driverClassName);
    }

    /** Connect with the driver from this configuration, rather than whichever
     * version DriverManager registered first for the same JDBC URL. */
    public static Connection connect(ConnectionConfig config, String driverClassName,
                                     String jdbcUrl, Properties properties) throws SQLException {
        String path = config.getDriverJarPath();
        Driver driver = instantiateDriver(path, driverClassName);
        ClassLoader loader = driver.getClass().getClassLoader();
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            Connection connection = driver.connect(jdbcUrl, properties);
            if (connection == null) {
                throw new SQLException("Configured JDBC driver rejected URL: " + jdbcUrl);
            }
            return connection;
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    /**
     * Load driver from external JAR file
     */
    private static void loadDriverFromJar(String driverJarPath, String driverClassName) {
        try {
            Driver driver = instantiateDriver(driverJarPath, driverClassName);
            DriverManager.registerDriver(new DriverProxy(driver, driver.getClass().getClassLoader()));
        } catch (Exception e) {
            throw new RuntimeException(
                String.format("Failed to load JDBC driver '%s' from %s", driverClassName, driverJarPath), e);
        }
    }

    private static Driver instantiateDriver(String driverJarPath, String driverClassName) {
        File driverJar = new File(driverJarPath);
        if (!driverJar.isFile()) {
            throw new IllegalArgumentException("Driver JAR file not found: " + driverJarPath);
        }
        String key = driverJar.getAbsolutePath() + ":" + driverJar.length() + ":" + driverJar.lastModified();
        URLClassLoader loader = DRIVER_LOADERS.computeIfAbsent(key, ignored -> {
            try {
                URL jarUrl = driverJar.toURI().toURL();
                return new URLClassLoader(new URL[]{jarUrl}, Thread.currentThread().getContextClassLoader());
            } catch (Exception e) {
                throw new RuntimeException("Failed to create class loader for driver JAR: " + driverJarPath, e);
            }
        });
        try {
            Class<?> driverClass = Class.forName(driverClassName, true, loader);
            return (Driver) driverClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load JDBC driver '" + driverClassName + "' from " + driverJarPath, e);
        }
    }

    /**
         * Driver proxy to use custom class loader
         */
        private record DriverProxy(Driver delegate, ClassLoader classLoader) implements Driver {

        @Override
            public Connection connect(String url, Properties info) throws SQLException {
                Thread currentThread = Thread.currentThread();
                ClassLoader originalLoader = currentThread.getContextClassLoader();
                try {
                    currentThread.setContextClassLoader(classLoader);
                    return delegate.connect(url, info);
                } finally {
                    currentThread.setContextClassLoader(originalLoader);
                }
            }

            @Override
            public boolean acceptsURL(String url) throws SQLException {
                return delegate.acceptsURL(url);
            }

            @Override
            public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
                return delegate.getPropertyInfo(url, info);
            }

            @Override
            public int getMajorVersion() {
                return delegate.getMajorVersion();
            }

            @Override
            public int getMinorVersion() {
                return delegate.getMinorVersion();
            }

            @Override
            public boolean jdbcCompliant() {
                return delegate.jdbcCompliant();
            }

            @Override
            public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                return delegate.getParentLogger();
            }
        }
}
