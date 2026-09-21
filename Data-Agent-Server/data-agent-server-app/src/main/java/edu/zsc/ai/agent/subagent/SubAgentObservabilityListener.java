package edu.zsc.ai.agent.subagent;

import edu.zsc.ai.agent.tool.AgentToolTracker;
import edu.zsc.ai.common.constant.AgentRuntimeLoggerNames;
import edu.zsc.ai.common.enums.ai.AgentTypeEnum;
import edu.zsc.ai.domain.model.dto.response.agent.ChatResponseBlock;
import edu.zsc.ai.domain.service.agent.SseEmitterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;

@Slf4j
public class SubAgentObservabilityListener {

    private static final Logger runtimeLog = LoggerFactory.getLogger(AgentRuntimeLoggerNames.SUB_AGENT);

    private final AgentTypeEnum agentType;
    private final Long conversationId;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final BiConsumer<String, Object> onToolExecutedCallback;
    private final String taskId;
    private final String parentToolCallId;
    private final Long connectionId;
    private final Long timeoutSeconds;
    private final AgentToolTracker toolTracker = new AgentToolTracker();

    private long invocationStartMs;

    public SubAgentObservabilityListener(AgentTypeEnum agentType,
                                         Long conversationId,
                                         SseEmitterRegistry sseEmitterRegistry,
                                         BiConsumer<String, Object> onToolExecutedCallback,
                                         String taskId,
                                         String parentToolCallId,
                                         Long connectionId,
                                         Long timeoutSeconds) {
        this.agentType = agentType;
        this.conversationId = conversationId;
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.onToolExecutedCallback = onToolExecutedCallback;
        this.taskId = taskId;
        this.parentToolCallId = parentToolCallId;
        this.connectionId = connectionId;
        this.timeoutSeconds = timeoutSeconds;
    }

    public void emitStart() {
        invocationStartMs = System.currentTimeMillis();
        emitSubAgentBlock(ChatResponseBlock.subAgentStart(agentType.getCode(), parentToolCallId, taskId, connectionId, timeoutSeconds));
        runtimeLog.info("subagent_start conversationId={} agentType={} taskId={} parentToolCallId={} connectionId={} timeoutSeconds={}",
                conversationId,
                agentType.getCode(),
                taskId,
                parentToolCallId,
                connectionId,
                timeoutSeconds);
    }

    public void emitComplete() {
        emitComplete(null, null);
    }

    public void emitComplete(String summaryText, String resultJson) {
        long durationMs = System.currentTimeMillis() - invocationStartMs;
        emitSubAgentBlock(ChatResponseBlock.subAgentComplete(
                agentType.getCode(), parentToolCallId, taskId,
                toolTracker.getTotalCount(), toolTracker.getToolCounts(), summaryText, resultJson, connectionId, timeoutSeconds));
        runtimeLog.info("subagent_complete conversationId={} agentType={} taskId={} parentToolCallId={} connectionId={} timeoutSeconds={} durationMs={} toolCount={} toolCounts={} summaryText={} resultJson={}",
                conversationId,
                agentType.getCode(),
                taskId,
                parentToolCallId,
                connectionId,
                timeoutSeconds,
                durationMs,
                toolTracker.getTotalCount(),
                toolTracker.getToolCounts(),
                summaryText,
                resultJson);
    }

    public void emitError(String errorMessage) {
        long durationMs = System.currentTimeMillis() - invocationStartMs;
        String errorMsg = errorMessage != null ? errorMessage : "unknown error";
        emitSubAgentBlock(ChatResponseBlock.subAgentError(agentType.getCode(), errorMsg, parentToolCallId, taskId, connectionId, timeoutSeconds));
        runtimeLog.error("subagent_error conversationId={} agentType={} taskId={} parentToolCallId={} connectionId={} timeoutSeconds={} durationMs={} errorMessage={}",
                conversationId,
                agentType.getCode(),
                taskId,
                parentToolCallId,
                connectionId,
                timeoutSeconds,
                durationMs,
                errorMsg);
    }

    public void recordToolExecution(String toolName, Object result) {
        if (toolName != null) {
            toolTracker.record(toolName);
        }
        if (onToolExecutedCallback != null && toolName != null) {
            try {
                onToolExecutedCallback.accept(toolName, result);
            } catch (Exception e) {
                log.warn("SubAgent tool callback failed", e);
            }
        }
    }

    private void emitSubAgentBlock(ChatResponseBlock block) {
        if (sseEmitterRegistry == null) {
            return;
        }
        sseEmitterRegistry.get(conversationId).ifPresent(sink -> sink.tryEmitNext(block));
    }
}
