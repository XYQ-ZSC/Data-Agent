package edu.zsc.ai.plugin.model.sql;

import java.util.Map;

/** Actual server version and readable session/database compatibility settings. */
public record CompletionEnvironment(String databaseVersion, Map<String, String> settings) {}
