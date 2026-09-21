package edu.zsc.ai.plugin.driver;

/**
 * Driver management constants.
 * Centralized configuration for driver download and storage.
 */
public final class DriverConstants {
    
    /**
     * Application directory name
     */
    public static final String APP_DIR_NAME = ".data-agent";
    
    /**
     * Drivers subdirectory name
     */
    public static final String DRIVERS_DIR_NAME = "drivers";
    
    /**
     * Default storage directory (in user home directory)
     */
    public static final String DEFAULT_STORAGE_DIR = 
        System.getProperty("user.home") + "/" + APP_DIR_NAME + "/" + DRIVERS_DIR_NAME;
    
    /**
     * Maven Central repository URL
     */
    public static final String MAVEN_CENTRAL_URL = "https://repo1.maven.org/maven2";
    
    private DriverConstants() {
        // Utility class
    }
}

