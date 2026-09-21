package edu.zsc.ai.domain.model.dto.request.db;

import edu.zsc.ai.api.model.request.BaseRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class UpdateTableRowRequest extends BaseRequest {

    @NotBlank(message = "tableName is required")
    private String tableName;

    @NotEmpty(message = "setValues must not be empty")
    private List<TableRowValueRequest> setValues;

    @NotEmpty(message = "matchValues must not be empty")
    private List<TableRowValueRequest> matchValues;

    private boolean force;
}
