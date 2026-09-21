package edu.zsc.ai.domain.event;

import org.springframework.context.ApplicationEvent;

public class ConversationDeletedEvent extends ApplicationEvent {

    private final Long conversationId;

    public ConversationDeletedEvent(Object source, Long conversationId) {
        super(source);
        this.conversationId = conversationId;
    }

    public Long getConversationId() {
        return conversationId;
    }
}
