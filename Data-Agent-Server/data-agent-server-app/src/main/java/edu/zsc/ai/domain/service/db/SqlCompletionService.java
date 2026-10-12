package edu.zsc.ai.domain.service.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.SqlCompletionResponse;

public interface SqlCompletionService {

    SqlCompletionResponse complete(DbContext db, String sql, int cursorOffset);

    SqlCompletionResponse dictionary(Long connectionId);
    SqlCompletionResponse dictionary(DbContext db, boolean resolveEnvironment);

    void invalidate(Long connectionId);
}
