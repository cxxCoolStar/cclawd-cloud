package ai.openagent.agent;

import java.util.Map;

/**
 * Agent 运行过程中产出的领域事件（V2 方案第 8 章事件的领域投影）
 *
 * <p>
 * Kernel 只表达「发生了什么」；wire 协议（seq 分配、持久化先行、
 * data 字段名与 fastclaw 前端对齐）由 bootstrap 的事件发布器负责。
 * ContentDelta 为高频瞬时事件不入库，其余均先持久化再广播
 * </p>
 */
public sealed interface AgentEvent {

    /**
     * 正文增量（瞬时，不入库）
     */
    record ContentDelta(String delta) implements AgentEvent {}

    /**
     * 思考过程增量（DeepSeek/Kimi 等模型的 reasoning_content，瞬时，不入库）
     */
    record ReasoningDelta(String delta) implements AgentEvent {}

    /**
     * 一段完整正文（模型返回 tool calls 前的 preamble 或最终回答）
     */
    record Content(String content, Map<String, Object> metadata) implements AgentEvent {

        public Content {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /**
     * 模型请求执行工具（data.id/name/arguments 与 fastclaw 前端一致，
     * arguments 保持 JSON 字符串）
     */
    record ToolCallRequested(String id, String name, String arguments) implements AgentEvent {}

    /**
     * 工具执行完成（data.id/name/result 与 fastclaw 前端一致）
     */
    record ToolResultProduced(String id, String name, String result) implements AgentEvent {}

    /**
     * 运行失败
     */
    record RunFailed(String message) implements AgentEvent {}

    /**
     * 运行收敛（无论终态如何，done 必达）
     */
    record Done() implements AgentEvent {}
}
