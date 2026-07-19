package ai.openagent.bootstrap.agent.controller;

import ai.openagent.bootstrap.agent.controller.request.AgentCreateRequest;
import ai.openagent.bootstrap.agent.controller.request.AgentUpdateRequest;
import ai.openagent.bootstrap.agent.controller.vo.AgentConfigVO;
import ai.openagent.bootstrap.agent.controller.vo.AgentVO;
import ai.openagent.bootstrap.agent.service.AgentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
     * 创建 Agent（V8 M3）：201 + {agent}，前端 createAgent 读 resp.agent.id；
     * name 必填校验，model/systemPrompt 缺省回落 ModelSettings
     */
    @PostMapping("/api/agents")
    public ResponseEntity<Map<String, AgentVO>> createAgent(@RequestBody @Valid AgentCreateRequest request) {
        AgentVO agent = agentService.createAgent(
                request.name(), request.description(), request.model(), request.systemPrompt());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("agent", agent));
    }

    /**
     * 删除 Agent（V8 M3）：级联清理会话/运行/工具/MCP/技能覆盖配置；
     * 种子默认 agent 拒绝删除（400）；workspace 目录保留
     */
    @DeleteMapping("/api/agents/{id}")
    public Map<String, Boolean> deleteAgent(@PathVariable String id) {
        agentService.deleteAgent(id);
        return Map.of("ok", true);
    }

    /**
     * 查询 Agent 配置（V6 最小形状：mcpServers，前端 MCP 页读取）
     */
    @GetMapping("/api/agents/{id}/config")
    public AgentConfigVO getAgentConfig(@PathVariable String id) {
        return agentService.getAgentConfig(id);
    }

    /**
     * 更新 Agent（V6：mcpServers 整表替换，缺省保持不动、显式 {} 清空；
     * V7 M3 增补 name/description/model 字段，null = 不动，model 空串 =
     * 清除覆盖回退种子默认值；其余前端字段忽略——见 V7 方案 3.4）
     */
    @PutMapping("/api/agents/{id}")
    public AgentConfigVO updateAgent(
            @PathVariable String id, @RequestBody @Valid AgentUpdateRequest request) {
        agentService.updateAgentProfile(id, request.name(), request.description(), request.model());
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

