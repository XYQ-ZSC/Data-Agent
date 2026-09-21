package edu.zsc.ai.domain.model.dto.request.db;

import edu.zsc.ai.api.model.request.BaseRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RenameTableRequest extends BaseRequest {

    @NotBlank(message = "tableName is required")
    private String tableName;

    @NotBlank(message = "newTableName is required")
    private String newTableName;
}
