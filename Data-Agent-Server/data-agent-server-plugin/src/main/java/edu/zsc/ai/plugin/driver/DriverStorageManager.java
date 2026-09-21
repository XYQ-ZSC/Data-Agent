package edu.zsc.ai.plugin.driver;

import edu.zsc.ai.plugin.enums.DbType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;
import org.apache.commons.lang3.StringUtils;

/**
 * Utility class for managing driver storage directories and files.
 */
public final class DriverStorageManager {
    
    private static final Logger logger = Logger.getLogger(DriverStorageManager.class.getName());
    
    private DriverStorageManager() {
        // Utility class
    }
    
    /**
     * Get storage directory path for a database type.
     *
     * @param baseStorageDir base storage directory
     * @param dbType database type
     * @return storage directory path
     */
    public static Path getStorageDirectory(String baseStorageDir, DbType dbType) {
        String storageDir = StringUtils.isNotBlank(baseStorageDir)
            ? baseStorageDir 
            : DriverConstants.DEFAULT_STORAGE_DIR;
        
        String dbTypeDir = dbType.getDisplayName();
        return Paths.get(storageDir, dbTypeDir);
    }
    
    /**
     * Get storage directory path using default base directory.
     *
     * @param dbType database type
     * @return storage directory path
     */
    public static Path getStorageDirectory(DbType dbType) {
        return getStorageDirectory(DriverConstants.DEFAULT_STORAGE_DIR, dbType);
    }
    
    /**
     * Get driver file path for given coordinates and database type.
     *
     * @param baseStorageDir base storage directory
     * @param dbType database type
     * @param coordinates Maven coordinates
     * @return driver file path
     */
    public static Path getDriverFilePath(String baseStorageDir, DbType dbType, MavenCoordinates coordinates) {
        Path storageDir = getStorageDirectory(baseStorageDir, dbType);
        String fileName = String.format("%s-%s.jar", coordinates.getArtifactId(), coordinates.getVersion());
        return storageDir.resolve(fileName);
    }
    
    /**
     * Get driver file path using default base directory.
     *
     * @param dbType database type
     * @param coordinates Maven coordinates
     * @return driver file path
     */
    public static Path getDriverFilePath(DbType dbType, MavenCoordinates coordinates) {
        return getDriverFilePath(DriverConstants.DEFAULT_STORAGE_DIR, dbType, coordinates);
    }
    
    /**
     * Ensure storage directory exists, creating it if necessary.
     *
     * @param storageDir storage directory path
     * @throws RuntimeException if directory creation fails
     */
    public static void ensureDirectoryExists(Path storageDir) {
        if (storageDir == null) {
            throw new IllegalArgumentException("Storage directory path is null");
        }
        
        if (!Files.exists(storageDir)) {
            try {
                Files.createDirectories(storageDir);
                logger.info("Created driver storage directory: " + storageDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create storage directory: " + storageDir, e);
            }
        } else if (!Files.isDirectory(storageDir)) {
            throw new RuntimeException("Storage path exists but is not a directory: " + storageDir);
        }
    }
    
    /**
     * Check if driver file already exists.
     *
     * @param filePath driver file path
     * @return true if file exists and is readable
     */
    public static boolean driverExists(Path filePath) {
        if (filePath == null) {
            return false;
        }
        return Files.exists(filePath) && Files.isRegularFile(filePath) && Files.isReadable(filePath);
    }
    
    /**
     * Delete a driver file.
     *
     * @param filePath driver file path
     * @throws RuntimeException if deletion fails
     */
    public static void deleteDriver(Path filePath) {
        if (filePath == null) {
            throw new IllegalArgumentException("File path is null");
        }
        
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("Driver file does not exist: " + filePath);
        }
        
        try {
            Files.delete(filePath);
            logger.info("Deleted driver file: " + filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete driver file: " + filePath, e);
        }
    }
}

