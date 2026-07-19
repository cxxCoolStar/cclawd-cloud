package ai.openagent.bootstrap.workspace.controller;

import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.workspace.WorkspaceHistoryService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace 版本历史接口
 *
 * <p>
 * GET 列出会话历史提交；POST 回滚整树到指定提交。
 * 回滚后前端的 Workspace 面板刷新文件列表即为旧版本内容
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class WorkspaceHistoryController {

    private final WorkspaceHistoryService historyService;
    private final AgentService agentService;

    /**
     * 列出会话历史提交（新到旧）
     */
    @GetMapping("/api/agents/{agentId}/sessions/{sessionId}/history")
    public Map<String, List<WorkspaceHistoryService.HistoryEntry>> listHistory(
            @PathVariable String agentId, @PathVariable String sessionId) {
        agentService.requireAccess(agentId);
        return Map.of("history", historyService.listHistory(agentId, sessionId));
    }

    /**
     * 回滚会话 workspace 到指定提交
     */
    @PostMapping("/api/agents/{agentId}/sessions/{sessionId}/history/restore")
    public Map<String, Boolean> restore(
            @PathVariable String agentId,
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body) {
        agentService.requireAccess(agentId);
        historyService.restore(agentId, sessionId, body.get("commit"));
        return Map.of("ok", true);
    }
}
