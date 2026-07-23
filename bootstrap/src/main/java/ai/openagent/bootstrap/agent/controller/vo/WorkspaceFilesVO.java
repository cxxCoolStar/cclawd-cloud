package ai.openagent.bootstrap.agent.controller.vo;

import ai.openagent.bootstrap.agent.service.AgentFileService;
import java.util.List;

public record WorkspaceFilesVO(List<AgentFileService.WorkspaceFileEntry> files) {}