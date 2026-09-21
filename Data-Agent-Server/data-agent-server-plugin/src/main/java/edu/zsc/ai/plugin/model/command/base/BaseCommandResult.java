package edu.zsc.ai.plugin.model.command.base;

import edu.zsc.ai.plugin.model.command.CommandResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BaseCommandResult implements CommandResult {

    private boolean success;


    private long executionTime;

    private String errorMessage;


    @Override
    public boolean isSuccess() {
        return success;
    }

    @Override
    public long getExecutionTime() {
        return executionTime;
    }
}
