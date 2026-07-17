package ai.openagent.bootstrap.agentrun;

import ai.openagent.agent.AgentEvent;
import ai.openagent.agent.AgentEventSink;
import ai.openagent.bootstrap.chat.event.ChatEventPublisher;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;

/**
 * Kernel 领域事件 → wire 协议事件的桥接（V2 方案第 8 章）
 *
 * <p>
 * 字段名与 fastclaw 前端消费完全一致：tool_call 用 data.id/name/arguments
 * （arguments 保持 JSON 字符串），tool_result 用 data.id/name/result；
 * content_delta 瞬时广播，其余先持久化再广播（含 done——前端靠它收敛）
 * </p>
 */
@RequiredArgsConstructor
public class WireAgentEventSink implements AgentEventSink {

    private final ChatEventPublisher publisher;
    private final String userId;
    private final String agentId;
    private final String sessionId;

    @Override
    public void emit(AgentEvent event) {
        if (event instanceof AgentEvent.ContentDelta delta) {
            publisher.publishTransient(agentId, sessionId, "content_delta", Map.of("delta", delta.delta()));
        } else if (event instanceof AgentEvent.Content content) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content", content.content());
            if (!content.metadata().isEmpty()) {
                data.put("metadata", content.metadata());
            }
            publisher.publishPersistent(userId, agentId, sessionId, "content", data);
        } else if (event instanceof AgentEvent.ToolCallRequested call) {
            publisher.publishPersistent(userId, agentId, sessionId, "tool_call", Map.of(
                    "id", call.id(),
                    "name", call.name(),
                    "arguments", call.arguments()));
        } else if (event instanceof AgentEvent.ToolResultProduced result) {
            publisher.publishPersistent(userId, agentId, sessionId, "tool_result", Map.of(
                    "id", result.id(),
                    "name", result.name(),
                    "result", result.result()));
        } else if (event instanceof AgentEvent.RunFailed failed) {
            publisher.publishPersistent(userId, agentId, sessionId, "error", Map.of(
                    "message", failed.message()));
        } else if (event instanceof AgentEvent.Done) {
            publisher.publishPersistent(userId, agentId, sessionId, "done", Map.of());
        }
    }
}
