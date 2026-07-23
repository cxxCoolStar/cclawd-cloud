package ai.openagent.bootstrap.workspace.controller;

import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.workspace.WorkspaceHistoryService;
import ai.openagent.bootstrap.workspace.controller.request.WorkspaceHistoryRestoreRequest;
import ai.openagent.bootstrap.workspace.controller.vo.WorkspaceHistoryVO;
import ai.openagent.framework.convention.Result;
import ai.openagent.framework.web.Results;
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
    public Result<WorkspaceHistoryVO> listHistory(
            @PathVariable String agentId, @PathVariable String sessionId) {
        agentService.requireAccess(agentId);
        return Results.success(new WorkspaceHistoryVO(historyService.listHistory(agentId, sessionId)));
    }

    /**
     * 回滚会话 workspace 到指定提交
     */
    @PostMapping("/api/agents/{agentId}/sessions/{sessionId}/history/restore")
    public Result<Void> restore(
            @PathVariable String agentId,
            @PathVariable String sessionId,
            @RequestBody WorkspaceHistoryRestoreRequest request) {
        agentService.requireAccess(agentId);
        historyService.restore(agentId, sessionId, request.commit());
        return Results.success();
    }
}
