package ai.openagent.bootstrap.agent.service;

import ai.openagent.bootstrap.agent.controller.vo.AgentConfigVO;
import ai.openagent.bootstrap.agent.controller.vo.AgentVO;
import ai.openagent.bootstrap.agent.service.bo.AgentBO;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 智能体服务接口
 */
public interface AgentService {

    /**
     * 归属与 scope 校验（V9 M2 统一防线，所有 agentId 端点在 service 入口调用）：
     * agent 不存在或当前用户非属主（super_admin 豁免）一律 404，不暴露存在性；
     * API Key 绑定了 agent 子集时目标 agent 不在子集内返回 403
     *
     * @return 校验通过后的 agent 记录
     */
    AgentBO requireAccess(String id);

    Optional<AgentBO> findById(String id);

    List<AgentBO> listByUser(String userId);

    /**
     * 查询当前用户的智能体列表（API Key 绑定子集时按子集过滤）
     */
    List<AgentVO> listAgents();

    /**
     * 按 ID 查询智能体（含归属校验），不存在或越权时抛资源不存在异常
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

    /**
     * 创建 Agent（V8 M3）：id 生成（agt_ 前缀随机 hex），model/systemPrompt
     * 缺省回落 ModelSettings；补种内置工具默认配置（与 DataSeeder 同源）
     */
    AgentVO createAgent(String name, String description, String model, String systemPrompt);

    /**
     * 删除 Agent 并级联清理（V8 M3）：sessions（+session_events/
     * session_messages）、agent_runs（+tool_executions）、agent_tools、
     * agent_mcp_servers 与 configs 的 skills.agentEntries.{id} 键；
     * 种子默认 agent 拒绝删除（400）；workspace 目录保留不删
     */
    void deleteAgent(String id);
}
