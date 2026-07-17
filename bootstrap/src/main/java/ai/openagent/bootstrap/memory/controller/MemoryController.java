package ai.openagent.bootstrap.memory.controller;

import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.memory.MemoryService;
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
 * 本接口为后端能力接口；字段形状：{"memory": "...", "user": "..."}
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
    public Map<String, String> getMemory(@PathVariable String agentId) {
        agentService.getAgent(agentId);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("memory", memoryService.loadMemory(agentId));
        result.put("user", memoryService.loadUserFile(agentId));
        return result;
    }

    /**
     * 更新 MEMORY.md / USER.md（只更新请求中出现的字段；写入前安全扫描，
     * 命中告警不阻断——fastclaw SaveMemoryWithScan 语义）
     */
    @PutMapping("/api/agents/{agentId}/memory")
    public Map<String, String> putMemory(@PathVariable String agentId, @RequestBody Map<String, String> body) {
        agentService.getAgent(agentId);
        if (body.containsKey("memory")) {
            memoryService.saveMemory(IdentityConstant.LOCAL_USER_ID, agentId, body.get("memory"));
        }
        if (body.containsKey("user")) {
            memoryService.saveUserFile(IdentityConstant.LOCAL_USER_ID, agentId, body.get("user"));
        }
        return getMemory(agentId);
    }
}
