package edu.zsc.ai.plugin.connection;

import edu.zsc.ai.plugin.driver.DriverStorageManager;
import edu.zsc.ai.plugin.enums.DbType;
import edu.zsc.ai.plugin.driver.MavenCoordinates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DriverStorageManager.
 */
class DriverStorageManagerTest {
    
    @TempDir
    Path tempDir;
    
    @Test
    void testGetStorageDirectory() {
        Path storageDir = DriverStorageManager.getStorageDirectory(
            tempDir.toString(),
            DbType.MYSQL
        );
        
        assertEquals(tempDir.resolve("MySQL"), storageDir);
    }
    
    @Test
    void testGetDriverFilePath() {
        MavenCoordinates coordinates = new MavenCoordinates(
            "com.mysql",
            "mysql-connector-j",
            "8.0.33"
        );
        
        Path driverPath = DriverStorageManager.getDriverFilePath(
            tempDir.toString(),
            DbType.MYSQL,
            coordinates
        );
        
        assertEquals(tempDir.resolve("MySQL/mysql-connector-j-8.0.33.jar"), driverPath);
    }
    
    @Test
    void testEnsureDirectoryExists_CreatesDirectory() {
        Path newDir = tempDir.resolve("TestDB");
        
        assertFalse(Files.exists(newDir));
        
        DriverStorageManager.ensureDirectoryExists(newDir);
        
        assertTrue(Files.exists(newDir));
        assertTrue(Files.isDirectory(newDir));
    }
    
    @Test
    void testEnsureDirectoryExists_ExistingDirectory() throws IOException {
        Path existingDir = tempDir.resolve("ExistingDir");
        Files.createDirectory(existingDir);
        
        assertTrue(Files.exists(existingDir));
        
        // Should not throw exception
        assertDoesNotThrow(() -> {
            DriverStorageManager.ensureDirectoryExists(existingDir);
        });
    }
    
    @Test
    void testEnsureDirectoryExists_PathIsFile() throws IOException {
        Path file = tempDir.resolve("file.txt");
        Files.createFile(file);
        
        assertThrows(RuntimeException.class, () -> {
            DriverStorageManager.ensureDirectoryExists(file);
        });
    }
    
    @Test
    void testDriverExists_FileExists() throws IOException {
        Path driverFile = tempDir.resolve("driver.jar");
        Files.createFile(driverFile);
        
        assertTrue(DriverStorageManager.driverExists(driverFile));
    }
    
    @Test
    void testDriverExists_FileDoesNotExist() {
        Path driverFile = tempDir.resolve("nonexistent.jar");
        
        assertFalse(DriverStorageManager.driverExists(driverFile));
    }
    
    @Test
    void testDriverExists_NullPath() {
        assertFalse(DriverStorageManager.driverExists(null));
    }
    
    @Test
    void testDeleteDriver_Success() throws IOException {
        Path driverFile = tempDir.resolve("driver.jar");
        Files.createFile(driverFile);
        
        assertTrue(Files.exists(driverFile));
        
        DriverStorageManager.deleteDriver(driverFile);
        
        assertFalse(Files.exists(driverFile));
    }
    
    @Test
    void testDeleteDriver_FileDoesNotExist() {
        Path driverFile = tempDir.resolve("nonexistent.jar");
        
        assertThrows(IllegalArgumentException.class, () -> {
            DriverStorageManager.deleteDriver(driverFile);
        });
    }
    
    @Test
    void testDeleteDriver_NullPath() {
        assertThrows(IllegalArgumentException.class, () -> {
            DriverStorageManager.deleteDriver(null);
        });
    }
}

