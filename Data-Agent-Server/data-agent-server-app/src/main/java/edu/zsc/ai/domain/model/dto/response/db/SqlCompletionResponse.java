package edu.zsc.ai.domain.model.dto.response.db;

import java.util.List;
import edu.zsc.ai.plugin.model.sql.CompletionEnvironment;

public record SqlCompletionResponse(List<SqlCompletionItem> items, boolean incomplete, CompletionEnvironment environment) {
    public SqlCompletionResponse(List<SqlCompletionItem> items, boolean incomplete) { this(items, incomplete, null); }
    public SqlCompletionResponse(List<SqlCompletionItem> items) { this(items, false); }
}
