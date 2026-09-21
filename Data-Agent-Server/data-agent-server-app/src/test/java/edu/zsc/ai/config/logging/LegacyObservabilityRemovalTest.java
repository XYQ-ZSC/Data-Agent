package edu.zsc.ai.config.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyObservabilityRemovalTest {

    @Test
    void legacyObservabilityClassesAreRemoved() {
        assertClassMissing("edu.zsc.ai.api.controller.ai.AgentObservabilityController");
        assertClassMissing("edu.zsc.ai.api.model.request.ai.AgentObservabilityUpdateRequest");
        assertClassMissing("edu.zsc.ai.config.ai.AgentObservabilityProperties");
        assertClassMissing("edu.zsc.ai.domain.service.ai.AgentObservabilityAdminService");
        assertClassMissing("edu.zsc.ai.domain.service.ai.impl.AgentObservabilityAdminServiceImpl");
        assertClassMissing("edu.zsc.ai.observability.AgentLogService");
        assertClassMissing("edu.zsc.ai.observability.DefaultAgentLogService");
        assertClassMissing("edu.zsc.ai.observability.handler.AgentRuntimeFileHandler");
        assertClassMissing("edu.zsc.ai.observability.config.DefaultAgentObservabilityConfigProvider");
    }

    private void assertClassMissing(String className) {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(className), className + " should be removed");
    }
}
