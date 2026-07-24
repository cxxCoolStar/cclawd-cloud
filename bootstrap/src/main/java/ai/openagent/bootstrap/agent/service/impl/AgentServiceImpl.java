package ai.openagent.bootstrap.agent.service.impl;

import ai.openagent.bootstrap.agent.controller.vo.AgentConfigVO;
import ai.openagent.bootstrap.agent.controller.vo.AgentVO;
import ai.openagent.bootstrap.agent.dao.entity.AgentDO;
import ai.openagent.bootstrap.agent.dao.mapper.AgentMapper;
import ai.openagent.bootstrap.agent.service.AgentDeletionService;
import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.agent.service.bo.AgentBO;
import ai.openagent.bootstrap.config.ModelSettings;
import ai.openagent.bootstrap.mcp.McpClientManager;
import ai.openagent.bootstrap.persistence.AgentMcpServerRecord;
import ai.openagent.bootstrap.persistence.AgentMcpServerRepository;
import ai.openagent.bootstrap.persistence.AgentToolRepository;
import ai.openagent.bootstrap.persistence.DataSeeder;
import ai.openagent.bootstrap.tool.ToolCatalog;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.exception.ServiceException;
import ai.openagent.framework.identity.RequestContext;
import ai.openagent.framework.identity.RequestIdentity;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final AgentMapper agentMapper;
    private final AgentDeletionService agentDeletionService;
    private final AgentMcpServerRepository mcpServerRepository;
    private final AgentToolRepository agentToolRepository;
    private final McpClientManager mcpClientManager;
    private final ObjectMapper objectMapper;
    private final ModelSettings modelSettings;

    @Override
    public AgentBO requireAccess(String id) {
        AgentBO agent = findById(id)
                .orElseThrow(() -> new ClientException("agent not found", BaseErrorCode.RESOURCE_NOT_FOUND));
        RequestIdentity identity = RequestContext.current()
                .orElseThrow(() -> new ClientException("unauthorized", BaseErrorCode.UNAUTHORIZED));
        if (!identity.isPlatformAdmin() && !agent.userId().equals(identity.userId())) {
            throw new ClientException("agent not found", BaseErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!identity.allowedAgentIds().isEmpty() && !identity.allowedAgentIds().contains(id)) {
            throw new ClientException("api key is not scoped to this agent", BaseErrorCode.FORBIDDEN);
        }
        return agent;
    }

    @Override
    public Optional<AgentBO> findById(String id) {
        return Optional.ofNullable(agentMapper.selectById(id)).map(AgentServiceImpl::toBO);
    }

    @Override
    public List<AgentBO> listByUser(String userId) {
        return agentMapper.selectList(Wrappers.<AgentDO>lambdaQuery()
                        .eq(AgentDO::getUserId, userId)
                        .orderByAsc(AgentDO::getCreatedAt))
                .stream()
                .map(AgentServiceImpl::toBO)
                .toList();
    }

    @Override
    public List<AgentVO> listAgents() {
        java.util.Set<String> scope = RequestContext.current()
                .map(RequestIdentity::allowedAgentIds)
                .orElse(java.util.Set.of());
        return listByUser(RequestContext.requireUserId()).stream()
                .filter(agent -> scope.isEmpty() || scope.contains(agent.id()))
                .map(AgentVO::from)
                .toList();
    }

    @Override
    public AgentVO getAgent(String id) {
        return AgentVO.from(requireAccess(id));
    }

    @Override
    public AgentConfigVO getAgentConfig(String id) {
        requireAccess(id);
        Map<String, AgentConfigVO.McpServerVO> servers = new LinkedHashMap<>();
        for (AgentMcpServerRecord record : mcpServerRepository.listByAgent(id)) {
            servers.put(record.name(), new AgentConfigVO.McpServerVO(
                    record.type(),
                    record.url(),
                    readMap(record.headersJson()),
                    record.command(),
                    readList(record.argsJson()),
                    readMap(record.envJson())));
        }
        return new AgentConfigVO(servers);
    }

    @Override
    public AgentConfigVO updateMcpServers(String id, Map<String, AgentConfigVO.McpServerVO> mcpServers) {
        requireAccess(id);
        List<AgentMcpServerRecord> records = mcpServers.entrySet().stream()
                .map(entry -> new AgentMcpServerRecord(
                        id,
                        entry.getKey(),
                        entry.getValue().type(),
                        value(entry.getValue().url()),
                        writeJson(entry.getValue().headers(), "{}"),
                        value(entry.getValue().command()),
                        writeJson(entry.getValue().args(), "[]"),
                        writeJson(entry.getValue().env(), "{}"),
                        0,
                        0))
                .toList();
        mcpServerRepository.replaceAll(id, records);
        mcpClientManager.evictAgent(id);
        return getAgentConfig(id);
    }

    @Override
    public void updateAgentProfile(String id, String name, String description, String model) {
        if (name == null && description == null && model == null) {
            return;
        }
        AgentBO agent = requireAccess(id);
        long now = System.currentTimeMillis();
        if (name != null || description != null) {
            AgentDO update = new AgentDO();
            update.setId(id);
            update.setName(name != null ? name : agent.name());
            update.setDescription(description != null ? description : agent.description());
            update.setUpdatedAt(now);
            agentMapper.updateById(update);
        }
        if (model != null) {
            AgentDO update = new AgentDO();
            update.setId(id);
            update.setModel(model.isBlank() ? modelSettings.name() : model);
            update.setUpdatedAt(now);
            agentMapper.updateById(update);
        }
    }

    @Override
    @Transactional
    public AgentVO createAgent(String name, String description, String model, String systemPrompt) {
        String id = "agt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long now = System.currentTimeMillis();
        AgentDO agent = new AgentDO();
        agent.setId(id);
        agent.setUserId(RequestContext.requireUserId());
        agent.setName(name.trim());
        agent.setDescription(value(description));
        agent.setProviderId(DataSeeder.DEFAULT_PROVIDER_ID);
        agent.setModel(model == null || model.isBlank() ? value(modelSettings.name()) : model);
        agent.setSystemPrompt(resolveSystemPrompt(systemPrompt));
        agent.setCreatedAt(now);
        agent.setUpdatedAt(now);
        agentMapper.insert(agent);
        for (ToolCatalog tool : ToolCatalog.BUILTIN_TOOLS) {
            agentToolRepository.upsert(id, tool.name(), tool.enabledDefault(), "{}");
        }
        return getAgent(id);
    }

    @Override
    public void deleteAgent(String id) {
        if (DataSeeder.DEFAULT_AGENT_ID.equals(id)) {
            throw new ClientException("the default agent cannot be deleted", BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        requireAccess(id);
        agentDeletionService.delete(id);
    }

    private String resolveSystemPrompt(String systemPrompt) {
        return systemPrompt != null && !systemPrompt.isBlank()
                ? systemPrompt
                : DataSeeder.resolveSystemPromptStatic(modelSettings);
    }

    private String writeJson(Object value, String emptyFallback) {
        if (value == null) {
            return emptyFallback;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new ServiceException("could not encode mcp server config", error, BaseErrorCode.SERVICE_ERROR);
        }
    }

    private Map<String, String> readMap(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return Map.of();
        }
    }

    private List<String> readList(String json) {
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    private static AgentBO toBO(AgentDO agent) {
        return new AgentBO(
                agent.getId(),
                agent.getUserId(),
                agent.getName(),
                agent.getDescription(),
                agent.getProviderId(),
                agent.getModel(),
                agent.getSystemPrompt(),
                agent.getCreatedAt(),
                agent.getUpdatedAt());
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
