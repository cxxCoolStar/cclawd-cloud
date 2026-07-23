package ai.openagent.bootstrap.workspace.service;

import ai.openagent.bootstrap.workspace.controller.request.WorkspaceHistoryRestoreRequest;
import ai.openagent.bootstrap.workspace.controller.vo.WorkspaceHistoryVO;

public interface WorkspaceHistoryApiService {

    WorkspaceHistoryVO listHistory(String agentId, String sessionId);

    void restore(String agentId, String sessionId, WorkspaceHistoryRestoreRequest request);
}