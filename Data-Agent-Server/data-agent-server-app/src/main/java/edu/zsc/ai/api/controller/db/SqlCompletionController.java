package edu.zsc.ai.api.controller.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.CompleteSqlRequest;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import edu.zsc.ai.domain.model.dto.response.db.SqlCompletionResponse;
import edu.zsc.ai.domain.service.db.SqlCompletionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/db/sql")
@RequiredArgsConstructor
public class SqlCompletionController {

    private final SqlCompletionService sqlCompletionService;

    @GetMapping("/completion-dictionary")
    public ApiResponse<SqlCompletionResponse> dictionary(@RequestParam Long connectionId) {
        return ApiResponse.success(sqlCompletionService.dictionary(connectionId));
    }

    @PostMapping("/completion-invalidate")
    public ApiResponse<Void> invalidate(@RequestParam Long connectionId) {
        sqlCompletionService.invalidate(connectionId);
        return ApiResponse.success(null);
    }

    @PostMapping("/complete")
    public ApiResponse<SqlCompletionResponse> complete(@Valid @RequestBody CompleteSqlRequest request) {
        DbContext db = new DbContext(request.connectionId(), request.catalog(), request.schema());
        return ApiResponse.success(sqlCompletionService.complete(db, request.sql(), request.cursorOffset()));
    }
}
