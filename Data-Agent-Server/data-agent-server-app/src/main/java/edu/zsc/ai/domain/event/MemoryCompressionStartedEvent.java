package edu.zsc.ai.domain.event;

import org.springframework.context.ApplicationEvent;

public class MemoryCompressionStartedEvent extends ApplicationEvent {

    private final Long conversationId;

    public MemoryCompressionStartedEvent(Object source, Long conversationId) {
        super(source);
        this.conversationId = conversationId;
    }

    public Long getConversationId() {
        return conversationId;
    }
}
