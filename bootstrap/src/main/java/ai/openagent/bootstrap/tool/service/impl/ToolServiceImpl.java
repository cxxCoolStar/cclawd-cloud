package ai.openagent.bootstrap.tool.service.impl;

import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.bootstrap.persistence.AgentRepository;
import ai.openagent.bootstrap.persistence.AgentToolRecord;
import ai.openagent.bootstrap.persistence.AgentToolRepository;
import ai.openagent.bootstrap.tool.CatalogToolRegistry;
import ai.openagent.bootstrap.tool.ToolCatalog;
import ai.openagent.bootstrap.tool.controller.vo.AgentToolVO;
import ai.openagent.bootstrap.tool.controller.vo.RegisteredToolVO;
import ai.openagent.bootstrap.tool.service.ToolService;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 工具管理服务实现（V7 方案 3.4）
 *
 * <p>
 * 启停语义：只写 agent_tools；exec 的全局 dockerEnabled 双重门控仍在
 * 执行侧，本服务不感知。MCP 工具（mcp_ 前缀）"server 配置即启用"，
 * 不经 agent_tools，启停请求返回 400 并提示走 MCP server 配置
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ToolServiceImpl implements ToolService {

    private final AgentRepository agentRepository;
    private final AgentToolRepository agentToolRepository;
    private final CatalogToolRegistry toolRegistry;

    @Override
    public List<AgentToolVO> listTools(String agentId) {
        requireAgent(agentId);
        Map<String, AgentToolRecord> configByName = agentToolRepository.listByAgent(agentId).stream()
                .collect(Collectors.toMap(AgentToolRecord::toolName, Function.identity()));
        List<AgentToolVO> tools = new ArrayList<>();
        // 内置：ToolCatalog ∩ 已装配实现（与 CatalogToolRegistry 同一交集来源）
        for (ToolDescriptor descriptor : toolRegistry.assembledBuiltinTools()) {
            ToolCatalog catalog = ToolCatalog.byName(descriptor.name()).orElseThrow();
            AgentToolRecord config = configByName.get(descriptor.name());
            tools.add(new AgentToolVO(
                    catalog.name(),
                    catalog.description(),
                    catalog.riskLevel(),
                    config != null && config.enabled(),
                    "builtin"));
        }
        // MCP：server 配置即启用，无风险级别
        for (ToolDescriptor descriptor : toolRegistry.availableTools(agentId)) {
            if (descriptor.source() == ToolDescriptor.Source.MCP) {
                tools.add(new AgentToolVO(descriptor.name(), descriptor.description(), null, true, "mcp"));
            }
        }
        return List.copyOf(tools);
    }

    @Override
    public void setToolEnabled(String agentId, String toolName, boolean enabled) {
        requireAgent(agentId);
        if (toolName.startsWith("mcp_")) {
            throw new ClientException(
                    "mcp tools are enabled via mcp server configuration, not per-tool toggle",
                    BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        if (ToolCatalog.byName(toolName).isEmpty()) {
            throw new ClientException("unknown tool: " + toolName, BaseErrorCode.RESOURCE_NOT_FOUND);
        }
        agentToolRepository.upsert(agentId, toolName, enabled, "{}");
    }

    @Override
    public List<RegisteredToolVO> listRegisteredTools(String agentId) {
        requireAgent(agentId);
        return toolRegistry.availableTools(agentId).stream()
                .map(descriptor -> new RegisteredToolVO(
                        descriptor.name(), descriptor.description(), sourceName(descriptor.source())))
                .toList();
    }

    private void requireAgent(String agentId) {
        if (!agentRepository.exists(agentId)) {
            throw new ClientException("agent not found", BaseErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private static String sourceName(ToolDescriptor.Source source) {
        return switch (source) {
            case BUILTIN -> "builtin";
            case MCP -> "mcp";
            default -> source.name().toLowerCase(java.util.Locale.ROOT);
        };
    }
}
