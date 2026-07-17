package ai.openagent.agent;

import ai.openagent.agent.tool.ToolResult;
import ai.openagent.infra.ai.model.ModelMessage;
import ai.openagent.infra.ai.model.ModelRequest;
import ai.openagent.infra.ai.model.ToolCall;
import ai.openagent.infra.ai.model.ToolDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 一次 Agent 运行的会话上下文端口（V2 方案第 5 章
 * ConversationContextBuilder 的运行期形态）
 *
 * <p>
 * bootstrap 实现负责：加载 system prompt + 历史消息、维护本次运行的
 * 上下文演进、把 assistant/tool 消息持久化进 session_messages、
 * 以 provider 配置构建 ModelRequest、解析会话 workspace 目录。
 * Kernel 只追加消息与构建请求，不接触数据库与配置
 * </p>
 */
public interface AgentConversation {

    /**
     * 以当前上下文构建模型请求
     *
     * @param tools          本次调用暴露给模型的工具（空列表则不携带 tools 字段）
     * @param transientNotes 仅本次调用附加的 system 提示（循环保护、
     *                       失败轮次、迭代上限 nudge——不进入持久化历史）
     */
    ModelRequest buildRequest(List<ToolDefinition> tools, List<ModelMessage> transientNotes);

    /**
     * 追加 assistant 消息（正文与 tool calls 并存时一并记录）并持久化
     */
    void appendAssistant(String content, List<ToolCall> toolCalls, String rawAssistantJson, Map<String, Object> metadata);

    /**
     * 追加 tool result 消息（role=tool，以 toolCallId 配对）并持久化
     */
    void appendToolResult(ToolCall call, ToolResult result);

    /**
     * 会话 workspace 目录（{workspaceRoot}/{agentId}/sessions/{sessionId}）
     */
    Path workspace();
}
