package ai.openagent.bootstrap.tool.controller;

import ai.openagent.bootstrap.tool.controller.request.ToolEnableRequest;
import ai.openagent.bootstrap.tool.controller.vo.AgentToolVO;
import ai.openagent.bootstrap.tool.controller.vo.RegisteredToolVO;
import ai.openagent.bootstrap.tool.controller.vo.ToolsConfigVO;
import ai.openagent.bootstrap.tool.service.ToolService;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具控制器（V7 方案 3.4：工具启停 API + 契约补齐）
 *
 * <p>
 * /api/agents/{id}/tools* 为 per-agent 工具管理视图与启停；
 * /api/tools 为全局工具配置占位——GET 返回空目录形状消除 404
 * （页面优雅降级），PUT 返回延期业务错误，完整 provider chain 留 V8
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class ToolController {

    private final ToolService toolService;

    /**
     * 管理视图：内置工具（含风险级别与启停状态）+ MCP 工具
     */
    @GetMapping("/api/agents/{id}/tools")
    public Map<String, List<AgentToolVO>> listTools(@PathVariable String id) {
        return Map.of("tools", toolService.listTools(id));
    }

    /**
     * 启停内置工具（mcp_ 前缀返回 400，提示走 MCP server 配置）
     */
    @PutMapping("/api/agents/{id}/tools/{toolName}")
    public Map<String, Boolean> setToolEnabled(
            @PathVariable String id,
            @PathVariable String toolName,
            @RequestBody @Valid ToolEnableRequest request) {
        toolService.setToolEnabled(id, toolName, request.enabled());
        return Map.of("ok", true);
    }

    /**
     * live registry 视图（前端 AgentRegisteredTool 契约）
     */
    @GetMapping("/api/agents/{id}/tools/registered")
    public Map<String, List<RegisteredToolVO>> listRegisteredTools(@PathVariable String id) {
        return Map.of("tools", toolService.listRegisteredTools(id));
    }

    /**
     * 全局工具配置（V7 占位：空目录形状，前端 ToolsConfig 契约）
     */
    @GetMapping("/api/tools")
    public ToolsConfigVO getTools() {
        return ToolsConfigVO.empty();
    }

    /**
     * 全局工具配置写入（V7 延期：返回业务错误，对齐总方案"延期接口"约定）
     */
    @PutMapping("/api/tools")
    public Map<String, Boolean> putTools() {
        throw new ClientException(
                "feature not available: tool provider configuration is planned for V8",
                BaseErrorCode.CLIENT_ERROR);
    }
}
