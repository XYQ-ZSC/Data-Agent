package edu.zsc.ai.agent.tool.sql.model;

/**
 * Minimal DTO representing an available connection for agent tools.
 */
public record AvailableConnectionItem(Long id, String name, String dbType) {
}
