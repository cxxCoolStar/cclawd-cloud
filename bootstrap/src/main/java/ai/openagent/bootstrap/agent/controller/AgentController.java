package ai.openagent.bootstrap.agent.controller;

import ai.openagent.bootstrap.agent.controller.vo.AgentVO;
import ai.openagent.bootstrap.agent.service.AgentService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能体控制器
 * 提供智能体的查询接口
 */
@RestController
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    /**
     * 查询智能体列表
     */
    @GetMapping("/api/agents")
    public Map<String, List<AgentVO>> listAgents() {
        return Map.of("agents", agentService.listAgents());
    }

    /**
     * 查询单个智能体
     */
    @GetMapping("/api/agents/{id}")
    public Map<String, AgentVO> getAgent(@PathVariable String id) {
        return Map.of("agent", agentService.getAgent(id));
    }
}
