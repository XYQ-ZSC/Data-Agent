package edu.zsc.ai.domain.model.dto.request.db;

import edu.zsc.ai.api.model.request.BaseRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** Cancel request scoped to the SQL execution's connection and workspace. */
@Data
@EqualsAndHashCode(callSuper = true)
public class CancelSqlRequest extends BaseRequest {
    @NotBlank
    private String executionId;
}
