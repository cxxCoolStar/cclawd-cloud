package ai.openagent.bootstrap.agent.service;

import ai.openagent.bootstrap.agent.controller.vo.AgentVO;
import java.util.List;

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
}
