package ai.openagent.bootstrap.agent.service.impl;

import ai.openagent.bootstrap.agent.controller.request.AgentCreateRequest;
import ai.openagent.bootstrap.agent.controller.request.AgentUpdateRequest;
import ai.openagent.bootstrap.agent.controller.vo.AgentConfigVO;
import ai.openagent.bootstrap.agent.controller.vo.AgentDetailVO;
import ai.openagent.bootstrap.agent.controller.vo.AgentListVO;
import ai.openagent.bootstrap.agent.service.AgentManagementService;
import ai.openagent.bootstrap.agent.service.AgentService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentManagementServiceImpl implements AgentManagementService {

    private final AgentService agentService;

    @Override
    public AgentListVO list() {
        return new AgentListVO(agentService.listAgents());
    }

    @Override
    public AgentDetailVO get(String id) {
        return new AgentDetailVO(agentService.getAgent(id));
    }

    @Override
    public AgentDetailVO create(AgentCreateRequest request) {
        return new AgentDetailVO(agentService.createAgent(
                request.name(), request.description(), request.model(), request.systemPrompt()));
    }

    @Override
    public AgentConfigVO config(String id) {
        return agentService.getAgentConfig(id);
    }

    @Override
    public AgentConfigVO update(String id, AgentUpdateRequest request) {
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
                        (first, second) -> second,
                        LinkedHashMap::new));
        return agentService.updateMcpServers(id, servers);
    }

    @Override
    public void delete(String id) {
        agentService.deleteAgent(id);
    }
}