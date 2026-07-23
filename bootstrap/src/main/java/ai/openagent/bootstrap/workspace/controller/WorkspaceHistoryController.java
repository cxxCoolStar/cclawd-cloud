package ai.openagent.bootstrap.workspace.controller;

import ai.openagent.bootstrap.workspace.controller.request.WorkspaceHistoryRestoreRequest;
import ai.openagent.bootstrap.workspace.controller.vo.WorkspaceHistoryVO;
import ai.openagent.bootstrap.workspace.service.WorkspaceHistoryApiService;
import ai.openagent.framework.convention.Result;
import ai.openagent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class WorkspaceHistoryController {

    private final WorkspaceHistoryApiService workspaceHistoryApiService;

    @GetMapping("/api/agents/{agentId}/sessions/{sessionId}/history")
    public Result<WorkspaceHistoryVO> listHistory(@PathVariable String agentId, @PathVariable String sessionId) {
        return Results.success(workspaceHistoryApiService.listHistory(agentId, sessionId));
    }

    @PostMapping("/api/agents/{agentId}/sessions/{sessionId}/history/restore")
    public Result<Void> restore(
            @PathVariable String agentId,
            @PathVariable String sessionId,
            @RequestBody WorkspaceHistoryRestoreRequest request) {
        workspaceHistoryApiService.restore(agentId, sessionId, request);
        return Results.success();
    }
}