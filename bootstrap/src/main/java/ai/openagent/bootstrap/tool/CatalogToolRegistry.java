package ai.openagent.bootstrap.tool;

import ai.openagent.agent.tool.AgentTool;
import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolRegistry;
import ai.openagent.agent.tool.ToolUnavailableException;
import ai.openagent.bootstrap.mcp.McpClientManager;
import ai.openagent.bootstrap.mcp.McpToolAdapter;
import ai.openagent.bootstrap.persistence.AgentToolRepository;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具注册表实现（V2 方案 7.3）
 *
 * <p>
 * 三重取交集决定模型可见工具（对齐 fastclaw registry「只有已注册且
 * 启用的工具能暴露给模型」）：
 * <ol>
 *   <li>{@link ToolCatalog} 白名单——平台声明支持的工具；</li>
 *   <li>Spring 容器中实际装配的 {@link AgentTool} 实现（M4 落地）；</li>
 *   <li>agent_tools 表中该 Agent 的启用配置。</li>
 * </ol>
 * M3 阶段尚无工具实现时 availableTools 为空列表，模型请求不携带
 * tools 字段，行为退化为普通聊天
 * </p>
 */
@Slf4j
@Component
public class CatalogToolRegistry implements ToolRegistry {

    private final AgentToolRepository agentToolRepository;
    private final McpClientManager mcpClientManager;
    private final Map<String, AgentTool> toolsByName;

    public CatalogToolRegistry(
            AgentToolRepository agentToolRepository,
            McpClientManager mcpClientManager,
            List<AgentTool> tools) {
        this.agentToolRepository = agentToolRepository;
        this.mcpClientManager = mcpClientManager;
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            String name = tool.descriptor().name();
            if (ToolCatalog.byName(name).isEmpty()) {
                // 目录是白名单唯一事实来源：未在目录声明的实现拒绝装配
                throw new IllegalStateException("tool implementation not in catalog: " + name);
            }
            byName.put(name, tool);
        }
        this.toolsByName = Map.copyOf(byName);
        log.info("[tool] 工具注册表就绪，已装配实现：{}", toolsByName.keySet());
    }

    @Override
    public List<ToolDescriptor> availableTools(String agentId) {
        Set<String> enabled = new HashSet<>(agentToolRepository.listEnabledToolNames(agentId));
        List<ToolDescriptor> builtin = toolsByName.values().stream()
                .map(AgentTool::descriptor)
                .filter(descriptor -> enabled.contains(descriptor.name()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        // V6：MCP 工具动态并入（server 配置即启用，不经 agent_tools；
        // 连接失败的 server 由 McpClientManager 记 WARN 跳过）
        for (McpToolAdapter.DiscoveredTool tool : mcpClientManager.discoverTools(agentId)) {
            builtin.add(tool.descriptor());
        }
        return List.copyOf(builtin);
    }

    /**
     * 已装配的内置工具描述（管理视图用：与 availableTools 的内置部分
     * 同一交集来源——ToolCatalog 白名单 ∩ Spring 装配实现，不看启停）
     */
    public List<ToolDescriptor> assembledBuiltinTools() {
        return toolsByName.values().stream().map(AgentTool::descriptor).toList();
    }

    @Override
    public AgentTool requireEnabled(String agentId, String toolName) {
        // MCP 工具（mcp_ 前缀）走动态适配
        if (toolName.startsWith("mcp_")) {
            return mcpClientManager.discoverTools(agentId).stream()
                    .filter(tool -> tool.prefixedName().equals(toolName))
                    .findFirst()
                    .map(tool -> new McpToolAdapter(tool, mcpClientManager))
                    .orElseThrow(() -> new ToolUnavailableException(
                            ToolErrorCode.TOOL_NOT_FOUND, "tool not found: " + toolName));
        }
        AgentTool tool = toolsByName.get(toolName);
        if (tool == null) {
            throw new ToolUnavailableException(
                    ToolErrorCode.TOOL_NOT_FOUND, "tool not found: " + toolName);
        }
        boolean enabled = agentToolRepository.find(agentId, toolName)
                .map(record -> record.enabled())
                .orElse(false);
        if (!enabled) {
            throw new ToolUnavailableException(
                    ToolErrorCode.TOOL_NOT_ENABLED, "tool not enabled for agent: " + toolName);
        }
        return tool;
    }
}
