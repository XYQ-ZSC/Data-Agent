package edu.zsc.ai.plugin.driver;


import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for querying Maven Central metadata API.
 * Retrieves available versions for a given artifact.
 */
public final class MavenMetadataClient {
    
    private static final Logger logger = Logger.getLogger(MavenMetadataClient.class.getName());
    
    /**
     * Connection timeout in milliseconds (30 seconds)
     */
    private static final int CONNECTION_TIMEOUT = 30000;
    
    /**
     * Read timeout in milliseconds (60 seconds)
     */
    private static final int READ_TIMEOUT = 60000;
    
    private MavenMetadataClient() {
        // Utility class
    }
    
    /**
     * Query available versions for a Maven artifact.
     *
     * @param groupId Maven group ID
     * @param artifactId Maven artifact ID
     * @param mavenRepositoryUrl Maven repository URL (default: Maven Central)
     * @return list of available versions, sorted by version number (newest first)
     * @throws RuntimeException if query fails
     */
    public static List<String> queryVersions(String groupId, String artifactId, String mavenRepositoryUrl) {
        
        if (groupId == null || groupId.isEmpty() || artifactId == null || artifactId.isEmpty()) {
            throw new IllegalArgumentException("groupId and artifactId are required");
        }
        
        String repoUrl = mavenRepositoryUrl != null && !mavenRepositoryUrl.isEmpty()
            ? mavenRepositoryUrl
            : DriverConstants.MAVEN_CENTRAL_URL;
        
        URL metadataUrl = MavenUrlBuilder.buildMetadataUrl(groupId, artifactId, repoUrl);
        
        logger.info("Querying Maven metadata from: " + metadataUrl);
        
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        
        try {
            connection = (HttpURLConnection) metadataUrl.openConnection();
            connection.setConnectTimeout(CONNECTION_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Data-Agent/1.0");
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new RuntimeException(
                    String.format("Failed to query Maven metadata from %s: HTTP %d", metadataUrl, responseCode));
            }
            
            inputStream = connection.getInputStream();
            List<String> versions = parseVersionsFromMetadata(inputStream);
            
            // Sort versions (newest first) - simple string comparison for now
            // In production, might want to use proper version comparison library
            versions.sort((v1, v2) -> {
                // Reverse order (newest first)
                return compareVersions(v2, v1);
            });
            
            logger.info(String.format("Found %d versions for %s:%s", versions.size(), groupId, artifactId));
            return versions;
            
        } catch (IOException e) {
            throw new RuntimeException(
                String.format("Failed to query Maven metadata from %s: %s", metadataUrl, e.getMessage()), e);
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
    
    /**
     * Query available versions using default Maven Central URL.
     *
     * @param groupId Maven group ID
     * @param artifactId Maven artifact ID
     * @return list of available versions
     * @throws RuntimeException if query fails
     */
    public static List<String> queryVersions(String groupId, String artifactId) {
        return queryVersions(groupId, artifactId, DriverConstants.MAVEN_CENTRAL_URL);
    }
    
    /**
     * Parse version list from Maven metadata XML.
     *
     * @param inputStream input stream containing XML
     * @return list of versions
     * @throws RuntimeException if parsing fails
     */
    private static List<String> parseVersionsFromMetadata(InputStream inputStream) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(inputStream);
            
            Element root = document.getDocumentElement();
            NodeList versionNodes = root.getElementsByTagName("version");
            
            List<String> versions = new ArrayList<>();
            for (int i = 0; i < versionNodes.getLength(); i++) {
                Element versionElement = (Element) versionNodes.item(i);
                String version = versionElement.getTextContent().trim();
                if (!version.isEmpty()) {
                    versions.add(version);
                }
            }
            
            return versions;
            
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new RuntimeException("Failed to parse Maven metadata XML: " + e.getMessage(), e);
        }
    }
    
    /**
     * Compare two version strings.
     * Simple implementation - compares version strings lexicographically after normalization.
     *
     * @param v1 version 1
     * @param v2 version 2
     * @return negative if v1 < v2, positive if v1 > v2, zero if equal
     */
    private static int compareVersions(String v1, String v2) {
        // Simple version comparison - split by dots and compare numerically
        String[] parts1 = v1.split("[\\.-]");
        String[] parts2 = v2.split("[\\.-]");
        
        int maxLength = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLength; i++) {
            int part1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int part2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            
            if (part1 != part2) {
                return Integer.compare(part1, part2);
            }
        }
        
        return v1.compareTo(v2);
    }
    
    /**
     * Parse a version part (number or string).
     *
     * @param part version part string
     * @return numeric value if possible, otherwise hash code
     */
    private static int parseVersionPart(String part) {
        try {
            // Try to extract number from string (e.g., "8.0.33" -> 8, "8.0" -> 8)
            Pattern pattern = Pattern.compile("^(\\d+)");
            Matcher matcher = pattern.matcher(part);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        } catch (NumberFormatException e) {
            // Ignore
        }
        return part.hashCode();
    }
}

