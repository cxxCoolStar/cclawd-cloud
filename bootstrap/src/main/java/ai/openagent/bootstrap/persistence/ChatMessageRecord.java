package ai.openagent.bootstrap.persistence;

/**
 * 会话消息记录
 *
 * <p>
 * role 允许 user / assistant / tool（V2 方案 9.4）；tool 消息以
 * toolCallId 与 assistant 的 tool call 配对；assistant 消息的
 * metadataJson 可携带 tool_calls 原始 JSON 与 UI metadata
 * </p>
 */
public record ChatMessageRecord(
        long seq,
        String role,
        String content,
        String provider,
        String model,
        String toolCallId,
        String toolName,
        String metadataJson,
        long createdAt) {}
