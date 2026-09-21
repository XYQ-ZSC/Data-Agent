package edu.zsc.ai.common.constant;

/**
 * Error message constants for chat and submit-tool-answer APIs.
 * Used when throwing ResponseStatusException from ChatServiceImpl.
 */
public final class ChatErrorConstants {

    private ChatErrorConstants() {}

    /** Prefix for unknown model error: message = UNKNOWN_MODEL_PREFIX + modelName */
    public static final String UNKNOWN_MODEL_PREFIX = "Unknown model: ";

    /** conversationId is required for submit-tool-answer. */
    public static final String CONVERSATION_ID_REQUIRED = "conversationId is required";

    /** No messages found for the conversation. */
    public static final String NO_MESSAGES_FOR_CONVERSATION = "No messages for this conversation";

    /** Prefix for no matching askUserQuestion tool result: message = prefix + toolCallId */
    public static final String NO_MATCHING_ASK_USER_TOOL_RESULT_PREFIX =
            "No matching askUserQuestion tool result for id: ";
}
