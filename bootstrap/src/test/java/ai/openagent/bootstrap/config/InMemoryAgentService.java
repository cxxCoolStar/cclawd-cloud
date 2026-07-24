package ai.openagent.bootstrap.config;

import ai.openagent.bootstrap.agent.controller.vo.AgentConfigVO;
import ai.openagent.bootstrap.agent.controller.vo.AgentVO;
import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.agent.service.bo.AgentBO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryAgentService implements AgentService {

    private final Map<String, AgentBO> agents = new LinkedHashMap<>();

    public void add(String agentId, String ownerId) {
        agents.put(agentId, new AgentBO(
                agentId, ownerId, agentId, "", "default-provider", "m", "", 0, 0));
    }

    @Override
    public AgentBO requireAccess(String id) {
        return findById(id).orElseThrow();
    }

    @Override
    public Optional<AgentBO> findById(String id) {
        return Optional.ofNullable(agents.get(id));
    }

    @Override
    public List<AgentBO> listByUser(String userId) {
        return agents.values().stream()
                .filter(agent -> agent.userId().equals(userId))
                .toList();
    }

    @Override
    public List<AgentVO> listAgents() {
        return agents.values().stream().map(AgentVO::from).toList();
    }

    @Override
    public AgentVO getAgent(String id) {
        return AgentVO.from(requireAccess(id));
    }

    @Override
    public AgentConfigVO getAgentConfig(String id) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AgentConfigVO updateMcpServers(String id, Map<String, AgentConfigVO.McpServerVO> mcpServers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void updateAgentProfile(String id, String name, String description, String model) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AgentVO createAgent(String name, String description, String model, String systemPrompt) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAgent(String id) {
        throw new UnsupportedOperationException();
    }
}
