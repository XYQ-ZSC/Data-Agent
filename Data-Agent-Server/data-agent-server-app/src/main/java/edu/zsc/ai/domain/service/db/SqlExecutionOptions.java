package edu.zsc.ai.domain.service.db;

/** Limits and identity supplied for one SQL execution request. */
public record SqlExecutionOptions(Integer timeoutMs, Integer maxRows, String executionId) {
    public static final SqlExecutionOptions DEFAULT = new SqlExecutionOptions(null, null, null);
}
