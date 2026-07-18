package ai.openagent.bootstrap.tool.service;

import ai.openagent.bootstrap.tool.controller.vo.AgentToolVO;
import ai.openagent.bootstrap.tool.controller.vo.RegisteredToolVO;
import java.util.List;

/**
 * 工具管理服务（V7 方案 3.4：工具启停 API + 契约补齐）
 */
public interface ToolService {

    /**
     * 管理视图：内置工具（ToolCatalog ∩ 已装配实现，含启停状态）+ MCP 工具
     */
    List<AgentToolVO> listTools(String agentId);

    /**
     * 启停内置工具（upsert agent_tools；mcp_ 前缀与目录外名字拒绝）
     */
    void setToolEnabled(String agentId, String toolName, boolean enabled);

    /**
     * live registry 视图：模型当前实际可见的工具（前端 registered 契约）
     */
    List<RegisteredToolVO> listRegisteredTools(String agentId);
}
