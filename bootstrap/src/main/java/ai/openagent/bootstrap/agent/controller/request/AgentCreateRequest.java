package ai.openagent.bootstrap.agent.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Agent 创建请求（V8 M3，POST /api/agents）
 *
 * <p>
 * 对齐前端 createAgent 的 Partial&lt;AgentDetail&gt;：name 必填，
 * description/model/systemPrompt 可选（缺省回落 ModelSettings 派生值）
 * </p>
 */
public record AgentCreateRequest(
        @NotBlank(message = "name required") @Size(max = 255, message = "name too long") String name,
        String description,
        String model,
        String systemPrompt) {}
