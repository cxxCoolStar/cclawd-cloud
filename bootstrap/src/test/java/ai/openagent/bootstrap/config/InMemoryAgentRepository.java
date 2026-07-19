package ai.openagent.bootstrap.config;

import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.AgentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 测试共用的内存版 AgentRepository（不触数据库；默认空库，
 * 经 {@link #add} 登记 agent 属主）
 */
public class InMemoryAgentRepository extends AgentRepository {

    private final Map<String, AgentRecord> agents = new LinkedHashMap<>();

    public InMemoryAgentRepository() {
        super(null);
    }

    public void add(String agentId, String ownerId) {
        agents.put(agentId, new AgentRecord(agentId, ownerId, agentId, "", "default-provider", "m", "", 0, 0));
    }

    @Override
    public Optional<AgentRecord> findById(String id) {
        return Optional.ofNullable(agents.get(id));
    }

    @Override
    public List<AgentRecord> listByUser(String userId) {
        return agents.values().stream()
                .filter(agent -> agent.userId().equals(userId))
                .toList();
    }
}
