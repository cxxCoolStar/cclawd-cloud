package ai.openagent.bootstrap.chat.service;

import ai.openagent.bootstrap.chat.controller.request.ChatStreamRequest;
import ai.openagent.bootstrap.chat.sse.ChatSseStream;

public interface ChatStreamService {

    ChatSseStream subscribe(String agentId, String sessionId, long cursor);

    ChatSseStream stream(ChatStreamRequest request);
}