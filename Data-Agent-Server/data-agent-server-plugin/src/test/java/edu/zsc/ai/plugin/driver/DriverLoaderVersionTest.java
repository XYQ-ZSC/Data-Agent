package edu.zsc.ai.plugin.driver;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DriverLoaderVersionTest {
    @TempDir Path tempDir;

    @Test
    void eachConnectionUsesItsSelectedDriverJar() throws Exception {
        Path firstJar = buildDriver("FIRST");
        Path secondJar = buildDriver("SECOND");
        ConnectionConfig first = new ConnectionConfig();
        first.setDriverJarPath(firstJar.toString());
        ConnectionConfig second = new ConnectionConfig();
        second.setDriverJarPath(secondJar.toString());

        SQLException firstFailure = assertThrows(SQLException.class, () -> DriverLoader.connect(
                first, "test.jdbc.VersionedDriver", "jdbc:versioned:test", new Properties()));
        SQLException secondFailure = assertThrows(SQLException.class, () -> DriverLoader.connect(
                second, "test.jdbc.VersionedDriver", "jdbc:versioned:test", new Properties()));

        assertEquals("FIRST", firstFailure.getMessage());
        assertEquals("SECOND", secondFailure.getMessage());
    }

    private Path buildDriver(String marker) throws Exception {
        Path root = Files.createDirectory(tempDir.resolve(marker));
        Path source = root.resolve("test/jdbc/VersionedDriver.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package test.jdbc;
                public final class VersionedDriver implements java.sql.Driver {
                    public java.sql.Connection connect(String url, java.util.Properties properties) throws java.sql.SQLException {
                        throw new java.sql.SQLException("%s");
                    }
                    public boolean acceptsURL(String url) { return true; }
                    public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties properties) {
                        return new java.sql.DriverPropertyInfo[0];
                    }
                    public int getMajorVersion() { return 1; }
                    public int getMinorVersion() { return 0; }
                    public boolean jdbcCompliant() { return false; }
                    public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
                }
                """.formatted(marker));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, source.toString()));
        Path classFile = source.resolveSibling("VersionedDriver.class");
        Path jar = tempDir.resolve(marker + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("test/jdbc/VersionedDriver.class"));
            Files.copy(classFile, output);
            output.closeEntry();
        }
        return jar;
    }
}
