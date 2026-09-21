package edu.zsc.ai.domain.service.agent;

import edu.zsc.ai.domain.model.dto.response.agent.ChatResponseBlock;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SseEmitterRegistry {

    private final ConcurrentHashMap<Long, Sinks.Many<ChatResponseBlock>> sinks = new ConcurrentHashMap<>();

    public void register(Long conversationId, Sinks.Many<ChatResponseBlock> sink) {
        sinks.put(conversationId, sink);
    }

    public void unregister(Long conversationId) {
        sinks.remove(conversationId);
    }

    public Optional<Sinks.Many<ChatResponseBlock>> get(Long conversationId) {
        return Optional.ofNullable(sinks.get(conversationId));
    }
}
