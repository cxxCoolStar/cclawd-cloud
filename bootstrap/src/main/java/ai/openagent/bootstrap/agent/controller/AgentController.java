package ai.openagent.bootstrap.agent.controller;

import ai.openagent.bootstrap.agent.controller.request.AgentUpdateRequest;
import ai.openagent.bootstrap.agent.controller.vo.AgentConfigVO;
import ai.openagent.bootstrap.agent.controller.vo.AgentVO;
import ai.openagent.bootstrap.agent.service.AgentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * 查询 Agent 配置（V6 最小形状：mcpServers，前端 MCP 页读取）
     */
    @GetMapping("/api/agents/{id}/config")
    public AgentConfigVO getAgentConfig(@PathVariable String id) {
        return agentService.getAgentConfig(id);
    }

    /**
     * 更新 Agent（V6 最小实现：仅 mcpServers 整表替换，其他字段忽略；
     * mcpServers 缺省保持不动（前端 "omit to leave untouched" 语义），
     * 显式 {} 清空）
     */
    @PutMapping("/api/agents/{id}")
    public AgentConfigVO updateAgent(
            @PathVariable String id, @RequestBody @Valid AgentUpdateRequest request) {
        if (request.mcpServers() == null) {
            return agentService.getAgentConfig(id);
        }
        Map<String, AgentConfigVO.McpServerVO> servers = request.mcpServers().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new AgentConfigVO.McpServerVO(
                                entry.getValue().type(),
                                entry.getValue().url(),
                                entry.getValue().headers(),
                                entry.getValue().command(),
                                entry.getValue().args(),
                                entry.getValue().env()),
                        (a, b) -> b,
                        java.util.LinkedHashMap::new));
        return agentService.updateMcpServers(id, servers);
    }
}

