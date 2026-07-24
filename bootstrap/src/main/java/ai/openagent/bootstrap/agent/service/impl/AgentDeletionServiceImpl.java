package ai.openagent.bootstrap.agent.service.impl;

import ai.openagent.bootstrap.agent.dao.mapper.AgentMapper;
import ai.openagent.bootstrap.agent.service.AgentDeletionService;
import ai.openagent.bootstrap.config.ConfigService;
import ai.openagent.bootstrap.mcp.McpClientManager;
import ai.openagent.bootstrap.persistence.ConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentDeletionServiceImpl implements AgentDeletionService {

    private final AgentMapper agentMapper;
    private final ConfigRepository configRepository;
    private final McpClientManager mcpClientManager;

    @Override
    @Transactional
    public void delete(String agentId) {
        agentMapper.deleteChannelInboundMessages(agentId);
        agentMapper.deleteChannelConversations(agentId);
        agentMapper.deleteChannelBindings(agentId);
        agentMapper.deleteSessionEvents(agentId);
        agentMapper.deleteSessionMessages(agentId);
        agentMapper.deleteSessions(agentId);
        agentMapper.deleteToolExecutions(agentId);
        agentMapper.deleteAgentRuns(agentId);
        agentMapper.deleteAgentTools(agentId);
        agentMapper.deleteAgentMcpServers(agentId);
        agentMapper.deleteById(agentId);
        configRepository.delete(ConfigService.KEY_SKILLS_AGENT_ENTRIES_PREFIX + agentId);
        mcpClientManager.evictAgent(agentId);
    }
}
