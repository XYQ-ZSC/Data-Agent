package edu.zsc.ai.agent.tool.sql.model;

/**
 * Flat search result with full location path for a database object.
 */
public record ObjectSearchResult(
        Long connectionId,
        String connectionName,
        String dbType,
        String databaseName,
        String schemaName,
        String objectName,
        String objectType
) {}
