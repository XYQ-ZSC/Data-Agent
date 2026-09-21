package edu.zsc.ai.config.logging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogbackConfigurationTest {

    @Test
    void applicationConfigDoesNotContainObservabilitySwitches() throws IOException {
        String application = readResource("application.yml");

        assertFalse(application.contains("agent:\n  observability:"));
        assertFalse(application.contains("runtime-log-enabled"));
        assertFalse(application.contains("console-log-enabled"));
        assertFalse(application.contains("sse-event-log-enabled"));
        assertFalse(application.contains("model-event-log-enabled"));
        assertFalse(application.contains("tool-event-log-enabled"));
        assertFalse(application.contains("include-response"));
        assertFalse(application.contains("include-token-stream"));
        assertFalse(application.contains("runtime-log-dir"));
    }

    @Test
    void profileConfigsDoNotContainObservabilitySwitches() throws IOException {
        String applicationDev = readResource("application-dev.yml");
        String applicationRelease = readResource("application-release.yml");

        assertFalse(applicationDev.contains("agent:\n  observability:"));
        assertFalse(applicationDev.contains("runtime-log-enabled"));
        assertFalse(applicationDev.contains("console-log-enabled"));
        assertFalse(applicationRelease.contains("agent:\n  observability:"));
        assertFalse(applicationRelease.contains("runtime-log-enabled"));
        assertFalse(applicationRelease.contains("console-log-enabled"));
    }

    @Test
    void logbackRoutesRuntimeLogsToFixedTextFile() throws IOException {
        String logback = readResource("logback-spring.xml");

        assertTrue(logback.contains(".data-agent/logs/agent/runtime"));
        assertTrue(logback.contains("agent-runtime.log"));
        assertTrue(logback.contains("agent.runtime"));
        assertTrue(logback.contains("RollingFileAppender"));
        assertFalse(logback.contains("jsonl"));
    }

    @Test
    void subAgentManagerDoesNotReferenceLegacyLoggingDecoratorBeans() throws IOException {
        String subAgentManager = readSource("edu/zsc/ai/config/ai/SubAgentManager.java");

        assertFalse(subAgentManager.contains("loggingExplorerSubAgent"));
        assertFalse(subAgentManager.contains("loggingPlannerSubAgent"));
    }

    private String readResource(String name) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Resource not found: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String readSource(String relativePath) throws IOException {
        Path path = Path.of("src/main/java").resolve(relativePath);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
