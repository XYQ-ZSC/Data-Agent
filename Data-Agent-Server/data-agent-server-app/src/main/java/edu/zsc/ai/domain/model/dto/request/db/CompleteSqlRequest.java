package edu.zsc.ai.domain.model.dto.request.db;

import jakarta.validation.constraints.NotNull;

public record CompleteSqlRequest(@NotNull Long connectionId, String catalog, String schema,
                                 @NotNull String sql, @NotNull Integer cursorOffset) {
}
