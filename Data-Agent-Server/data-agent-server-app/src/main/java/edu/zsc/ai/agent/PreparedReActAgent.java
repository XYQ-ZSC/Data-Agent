package edu.zsc.ai.agent;

import edu.zsc.ai.common.enums.ai.PromptEnum;

public record PreparedReActAgent(
        ReActAgent agent,
        String systemPrompt,
        PromptEnum promptEnum
) {
}
