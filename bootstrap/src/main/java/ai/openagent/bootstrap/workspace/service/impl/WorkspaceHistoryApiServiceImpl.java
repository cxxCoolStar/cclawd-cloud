package ai.openagent.bootstrap.workspace.service.impl;

import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.workspace.WorkspaceHistoryService;
import ai.openagent.bootstrap.workspace.controller.request.WorkspaceHistoryRestoreRequest;
import ai.openagent.bootstrap.workspace.controller.vo.WorkspaceHistoryVO;
import ai.openagent.bootstrap.workspace.service.WorkspaceHistoryApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WorkspaceHistoryApiServiceImpl implements WorkspaceHistoryApiService {

    private final WorkspaceHistoryService historyService;
    private final AgentService agentService;

    @Override
    public WorkspaceHistoryVO listHistory(String agentId, String sessionId) {
        agentService.requireAccess(agentId);
        return new WorkspaceHistoryVO(historyService.listHistory(agentId, sessionId));
    }

    @Override
    public void restore(String agentId, String sessionId, WorkspaceHistoryRestoreRequest request) {
        agentService.requireAccess(agentId);
        historyService.restore(agentId, sessionId, request.commit());
    }
}