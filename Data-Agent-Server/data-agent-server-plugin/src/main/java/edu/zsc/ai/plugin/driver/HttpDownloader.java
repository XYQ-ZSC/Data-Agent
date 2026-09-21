package edu.zsc.ai.plugin.driver;


import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * Utility class for downloading files via HTTP.
 */
public final class HttpDownloader {
    
    private static final Logger logger = Logger.getLogger(HttpDownloader.class.getName());
    
    /**
     * Connection timeout in milliseconds (30 seconds)
     */
    private static final int CONNECTION_TIMEOUT = 30000;
    
    /**
     * Read timeout in milliseconds (60 seconds)
     */
    private static final int READ_TIMEOUT = 60000;
    
    private HttpDownloader() {
        // Utility class
    }
    
    /**
     * Download a file from URL and save to target path.
     *
     * @param url URL to download from
     * @param targetPath target file path
     * @throws RuntimeException if download fails
     */
    public static void download(URL url, Path targetPath) {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECTION_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Data-Agent/1.0");
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException(
                    String.format("Failed to download file from %s: HTTP %d", url, responseCode));
            }
            
            // Create parent directories if needed
            Files.createDirectories(targetPath.getParent());
            
            // Download file
            inputStream = connection.getInputStream();
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            
            logger.info(String.format("Successfully downloaded file from %s to %s", url, targetPath));
            
        } catch (IOException e) {
            // Clean up partial file if exists
            try {
                if (Files.exists(targetPath)) {
                    Files.delete(targetPath);
                }
            } catch (IOException deleteException) {
                logger.warning("Failed to delete partial file: " + deleteException.getMessage());
            }
            
            throw new RuntimeException(
                String.format("Failed to download file from %s: %s", url, e.getMessage()), e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    logger.warning("Failed to close input stream: " + e.getMessage());
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}

