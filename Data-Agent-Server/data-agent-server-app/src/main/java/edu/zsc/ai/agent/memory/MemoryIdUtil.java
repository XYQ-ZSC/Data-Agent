package edu.zsc.ai.agent.memory;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class MemoryIdUtil {

    private MemoryIdUtil() {
    }

    /**
     * Builds a memoryId string in the format "userId:conversationId:modelName".
     */
    public static String build(Long userId, Long conversationId, String modelName) {
        return userId + ":" + conversationId + ":" + modelName;
    }

    /**
     * Parses a memoryId string in the format "userId:conversationId[:modelName]".
     *
     * @return parsed MemoryIdInfo, or null if the format is invalid
     */
    public static MemoryIdInfo parse(Object memoryId) {
        if (memoryId == null) {
            return null;
        }

        String id = memoryId.toString();
        String[] parts = id.split(":");

        if (parts.length < 2 || parts.length > 3) {
            log.warn("Invalid memoryId format: '{}', expected 'userId:conversationId[:modelName]'", id);
            return null;
        }

        try {
            return new MemoryIdInfo(
                    Long.parseLong(parts[0]),
                    Long.parseLong(parts[1]),
                    parts.length == 3 ? parts[2] : null);
        } catch (NumberFormatException e) {
            log.warn("Invalid memoryId format: '{}', expected 'userId:conversationId[:modelName]'", id, e);
            return null;
        }
    }
}
