package ai.openagent.bootstrap.memory.controller;

import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.memory.MemoryService;
import ai.openagent.bootstrap.memory.controller.request.MemoryUpdateRequest;
import ai.openagent.bootstrap.memory.controller.vo.MemoryVO;
import ai.openagent.framework.convention.Result;
import ai.openagent.framework.identity.RequestContext;
import ai.openagent.framework.web.Results;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆读写接口（V3 方案 5.1 最小 API）
 *
 * <p>
 * 前端现有代码暂无记忆消费路径（2026-07-17 核查 frontend/src/lib/api.ts），
 * 本接口为后端能力接口；业务数据字段形状：{"memory": "...", "user": "..."}
 * 对应 MEMORY.md 与 USER.md
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryService memoryService;
    private final AgentService agentService;

    /**
     * 读取 Agent 的 MEMORY.md 与 USER.md 内容
     */
    @GetMapping("/api/agents/{agentId}/memory")
    public Result<MemoryVO> getMemory(@PathVariable String agentId) {
        agentService.getAgent(agentId);
        return Results.success(loadMemory(agentId));
    }

    /**
     * 更新 MEMORY.md / USER.md（只更新请求中出现的字段；写入前安全扫描，
     * 命中告警不阻断）
     */
    @PutMapping("/api/agents/{agentId}/memory")
    public Result<MemoryVO> putMemory(@PathVariable String agentId, @RequestBody MemoryUpdateRequest request) {
        agentService.getAgent(agentId);
        if (request.memory() != null) {
            memoryService.saveMemory(RequestContext.requireUserId(), agentId, request.memory());
        }
        if (request.user() != null) {
            memoryService.saveUserFile(RequestContext.requireUserId(), agentId, request.user());
        }
        return Results.success(loadMemory(agentId));
    }

    private MemoryVO loadMemory(String agentId) {
        return new MemoryVO(memoryService.loadMemory(agentId), memoryService.loadUserFile(agentId));
    }
}
