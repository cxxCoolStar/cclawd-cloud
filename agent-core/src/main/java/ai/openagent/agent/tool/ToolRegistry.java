package ai.openagent.agent.tool;

import java.util.List;

/**
 * 工具注册表端口（V2 方案 7.3）
 *
 * <p>
 * 只有「平台已实现 且 该 Agent 已启用」的工具会暴露给模型
 * （对齐 fastclaw registry：Only registered and enabled tools are
 * exposed）。bootstrap 侧实现基于 ToolCatalog 白名单 + agent_tools
 * 启停配置 + 已装配的 {@link AgentTool} Bean 三者取交集
 * </p>
 */
public interface ToolRegistry {

    /**
     * 该 Agent 当前可用（已实现且已启用）的工具描述符
     */
    List<ToolDescriptor> availableTools(String agentId);

    /**
     * 解析已启用的工具实现
     *
     * @throws ToolUnavailableException 工具未注册（TOOL_NOT_FOUND）
     *                                  或未为该 Agent 启用（TOOL_NOT_ENABLED）
     */
    AgentTool requireEnabled(String agentId, String toolName);
}
