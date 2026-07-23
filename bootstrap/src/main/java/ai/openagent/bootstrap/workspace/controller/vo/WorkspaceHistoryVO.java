package ai.openagent.bootstrap.workspace.controller.vo;

import ai.openagent.bootstrap.workspace.WorkspaceHistoryService;
import java.util.List;

public record WorkspaceHistoryVO(List<WorkspaceHistoryService.HistoryEntry> history) {}
