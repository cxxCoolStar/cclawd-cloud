package ai.openagent.bootstrap.agent.service;

import ai.openagent.bootstrap.agent.controller.vo.AgentConfigVO;
import ai.openagent.bootstrap.agent.controller.vo.AgentVO;
import java.util.List;
import java.util.Map;

/**
 * 智能体服务接口
 */
public interface AgentService {

    /**
     * 查询当前用户的智能体列表
     */
    List<AgentVO> listAgents();

    /**
     * 按 ID 查询智能体，不存在时抛资源不存在异常
     */
    AgentVO getAgent(String id);

    /**
     * 查询 Agent 配置（V6 最小形状：mcpServers）
     */
    AgentConfigVO getAgentConfig(String id);

    /**
     * 整表替换 Agent 的 MCP Server 配置（驱逐缓存客户端，下次调用重连）
     */
    AgentConfigVO updateMcpServers(String id, Map<String, AgentConfigVO.McpServerVO> mcpServers);

    /**
     * 更新 Agent 基础字段（V7 M3）：name/description/model 为 null 时不动；
     * model 为空串时清除覆盖、回退种子默认值（ModelSettings）
     */
    void updateAgentProfile(String id, String name, String description, String model);
}
