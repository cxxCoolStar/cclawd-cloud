package ai.openagent.bootstrap.chat.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发起聊天回合请求
 *
 * @param agentId   智能体 ID
 * @param sessionId 会话 ID
 * @param message   用户消息
 */
public record ChatStreamRequest(
        @NotBlank(message = "agentId required") String agentId,
        @NotBlank(message = "valid sessionId required") @Size(max = 128, message = "valid sessionId required")
                String sessionId,
        @NotBlank(message = "message required") String message) {}
