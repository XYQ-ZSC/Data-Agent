package edu.zsc.ai.plugin.connection;

import edu.zsc.ai.plugin.driver.JarFileValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JarFileValidator.
 */
class JarFileValidatorTest {
    
    @TempDir
    Path tempDir;
    
    /**
     * JAR file magic number (ZIP format)
     */
    private static final byte[] JAR_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    
    @Test
    void testValidate_ValidJarFile() throws IOException {
        // Create a minimal valid JAR file
        Path jarFile = tempDir.resolve("test.jar");
        createMinimalJarFile(jarFile);
        
        // Should not throw exception
        assertDoesNotThrow(() -> {
            JarFileValidator.validate(jarFile);
        });
    }
    
    /**
     * Create a minimal valid JAR file for testing.
     */
    private void createMinimalJarFile(Path jarFile) throws IOException {
        // Use java.util.jar.JarOutputStream to create a minimal valid JAR
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                Files.newOutputStream(jarFile))) {
            // Add a minimal manifest entry
            jos.putNextEntry(new java.util.jar.JarEntry("META-INF/MANIFEST.MF"));
            jos.write("Manifest-Version: 1.0\n".getBytes());
            jos.closeEntry();
        }
    }
    
    @Test
    void testValidate_FileDoesNotExist() {
        Path nonExistentFile = tempDir.resolve("nonexistent.jar");
        
        assertThrows(IllegalArgumentException.class, () -> {
            JarFileValidator.validate(nonExistentFile);
        });
    }
    
    @Test
    void testValidate_EmptyFile() throws IOException {
        Path emptyFile = tempDir.resolve("empty.jar");
        Files.createFile(emptyFile);
        
        assertThrows(RuntimeException.class, () -> {
            JarFileValidator.validate(emptyFile);
        });
    }
    
    @Test
    void testValidate_InvalidMagicNumber() throws IOException {
        Path invalidFile = tempDir.resolve("invalid.jar");
        Files.write(invalidFile, new byte[]{0x00, 0x01, 0x02, 0x03});
        
        assertThrows(RuntimeException.class, () -> {
            JarFileValidator.validate(invalidFile);
        });
    }
    
    @Test
    void testValidate_FileTooSmall() throws IOException {
        Path smallFile = tempDir.resolve("small.jar");
        Files.write(smallFile, new byte[]{0x50, 0x4B}); // Only 2 bytes
        
        assertThrows(RuntimeException.class, () -> {
            JarFileValidator.validate(smallFile);
        });
    }
    
    @Test
    void testValidate_NullPath() {
        assertThrows(IllegalArgumentException.class, () -> {
            JarFileValidator.validate(null);
        });
    }
}

