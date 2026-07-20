package ai.openagent.bootstrap.agent.service.impl;

import ai.openagent.bootstrap.agent.controller.vo.AgentConfigVO;
import ai.openagent.bootstrap.agent.controller.vo.AgentVO;
import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.config.ConfigService;
import ai.openagent.bootstrap.config.ModelSettings;
import ai.openagent.bootstrap.mcp.McpClientManager;
import ai.openagent.bootstrap.persistence.AgentMcpServerRecord;
import ai.openagent.bootstrap.persistence.AgentMcpServerRepository;
import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.AgentRepository;
import ai.openagent.bootstrap.persistence.AgentToolRepository;
import ai.openagent.bootstrap.persistence.ConfigRepository;
import ai.openagent.bootstrap.persistence.DataSeeder;
import ai.openagent.bootstrap.tool.ToolCatalog;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.exception.ServiceException;
import ai.openagent.framework.identity.RequestContext;
import ai.openagent.framework.identity.RequestIdentity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 智能体服务实现
 */
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final AgentRepository agentRepository;
    private final AgentMcpServerRepository mcpServerRepository;
    private final AgentToolRepository agentToolRepository;
    private final ConfigRepository configRepository;
    private final McpClientManager mcpClientManager;
    private final ObjectMapper objectMapper;
    private final ModelSettings modelSettings;

    @Override
    public AgentRecord requireAccess(String id) {
        AgentRecord agent = agentRepository
                .findById(id)
                .orElseThrow(() -> new ClientException("agent not found", BaseErrorCode.RESOURCE_NOT_FOUND));
        RequestIdentity identity = RequestContext.current()
                .orElseThrow(() -> new ClientException("unauthorized", BaseErrorCode.UNAUTHORIZED));
        if (!identity.isPlatformAdmin() && !agent.userId().equals(identity.userId())) {
            // 越权按不存在处理，不暴露 agent 存在性
            throw new ClientException("agent not found", BaseErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!identity.allowedAgentIds().isEmpty() && !identity.allowedAgentIds().contains(id)) {
            throw new ClientException("api key is not scoped to this agent", BaseErrorCode.FORBIDDEN);
        }
        return agent;
    }

    @Override
    public List<AgentVO> listAgents() {
        java.util.Set<String> scope = RequestContext.current()
                .map(RequestIdentity::allowedAgentIds)
                .orElse(java.util.Set.of());
        return agentRepository.listByUser(RequestContext.requireUserId()).stream()
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
        getAgent(id);
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
        getAgent(id);
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
        AgentRecord agent = requireAccess(id);
        long now = System.currentTimeMillis();
        if (name != null || description != null) {
            agentRepository.updateProfile(
                    id,
                    name != null ? name : agent.name(),
                    description != null ? description : agent.description(),
                    now);
        }
        if (model != null) {
            // 空串 = 清除覆盖，回退种子默认值（与 DataSeeder 同源）
            agentRepository.updateModel(id, model.isBlank() ? modelSettings.name() : model, now);
        }
    }

    @Override
    @Transactional
    public AgentVO createAgent(String name, String description, String model, String systemPrompt) {
        String id = "agt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long now = System.currentTimeMillis();
        agentRepository.insert(
                id,
                RequestContext.requireUserId(),
                name.trim(),
                value(description),
                DataSeeder.DEFAULT_PROVIDER_ID,
                model == null || model.isBlank() ? value(modelSettings.name()) : model,
                resolveSystemPrompt(systemPrompt),
                now);
        // 与 DataSeeder 同源：补种内置工具默认启停
        for (ToolCatalog tool : ToolCatalog.BUILTIN_TOOLS) {
            agentToolRepository.upsert(id, tool.name(), tool.enabledDefault(), "{}");
        }
        return getAgent(id);
    }

    /**
     * 解析系统提示词：如果传入为空，则复用 DataSeeder 的 fallback 逻辑
     * 优先级：1) 传入值（非空） 2) 环境变量/modelSettings 3) classpath:system-prompt.md 4) 最简默认值
     */
    private String resolveSystemPrompt(String inputSystemPrompt) {
        // 1. 使用传入值
        if (inputSystemPrompt != null && !inputSystemPrompt.isBlank()) {
            return inputSystemPrompt;
        }
        // 2. 复用 DataSeeder 的完整 fallback 链（env → classpath file → default）
        return DataSeeder.resolveSystemPromptStatic(modelSettings);
    }

    @Override
    @Transactional
    public void deleteAgent(String id) {
        if (DataSeeder.DEFAULT_AGENT_ID.equals(id)) {
            throw new ClientException("the default agent cannot be deleted", BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        getAgent(id);
        agentRepository.deleteCascade(id);
        configRepository.delete(ConfigService.KEY_SKILLS_AGENT_ENTRIES_PREFIX + id);
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

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
