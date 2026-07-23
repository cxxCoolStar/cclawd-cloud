package ai.openagent.bootstrap.chat.service.impl;

import ai.openagent.bootstrap.agentrun.AgentRunCoordinator;
import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.chat.controller.request.ChatStreamRequest;
import ai.openagent.bootstrap.chat.service.ChatService;
import ai.openagent.bootstrap.chat.service.ChatStreamService;
import ai.openagent.bootstrap.chat.sse.ChatSseStream;
import ai.openagent.bootstrap.chat.sse.ChatSseStreamFactory;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatStreamServiceImpl implements ChatStreamService {

    private final ChatService chatService;
    private final AgentRunCoordinator runCoordinator;
    private final ChatSseStreamFactory sseStreamFactory;
    private final AgentService agentService;

    @Override
    public ChatSseStream subscribe(String agentId, String sessionId, long cursor) {
        ChatSseStream stream = sseStreamFactory.openSubscribeStream(cursor);
        try {
            sseStreamFactory.connect(stream, agentId, sessionId);
            stream.comment("ok");
            List<Map<String, Object>> replayed = chatService.replayEventsSince(agentId, sessionId, cursor);
            stream.replay(replayed);
            stream.goLive();
            return stream;
        } catch (RuntimeException error) {
            stream.close();
            throw error;
        }
    }

    @Override
    public ChatSseStream stream(ChatStreamRequest request) {
        agentService.requireAccess(request.agentId());
        ChatSseStream stream = sseStreamFactory.openTurnStream();
        try {
            sseStreamFactory.connect(stream, request.agentId(), request.sessionId());
            runCoordinator.start(request.agentId(), request.sessionId(), request.message());
            return stream;
        } catch (RuntimeException error) {
            stream.close();
            throw error;
        }
    }
}