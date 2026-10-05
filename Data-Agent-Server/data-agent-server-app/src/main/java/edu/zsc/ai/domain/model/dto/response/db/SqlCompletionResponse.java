package edu.zsc.ai.domain.model.dto.response.db;

import java.util.List;

public record SqlCompletionResponse(List<SqlCompletionItem> items, boolean incomplete) {
    public SqlCompletionResponse(List<SqlCompletionItem> items) { this(items, false); }
}
