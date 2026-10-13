package edu.zsc.ai.domain.model.dto.request.db;

import edu.zsc.ai.api.model.request.BaseRequest;
import edu.zsc.ai.plugin.model.transaction.BatchMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** SQL script execution with explicit transaction semantics. */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExecuteSqlBatchRequest extends BaseRequest {
    @NotBlank
    private String sql;

    private BatchMode mode = BatchMode.ATOMIC;

    @Min(1) @Max(300000)
    private Integer timeoutMs;

    @Min(1) @Max(100000)
    private Integer maxRows;

    private String executionId;
}
