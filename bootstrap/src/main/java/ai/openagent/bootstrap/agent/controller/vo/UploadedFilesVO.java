package ai.openagent.bootstrap.agent.controller.vo;

import ai.openagent.bootstrap.agent.service.AgentFileService;
import java.util.List;

public record UploadedFilesVO(List<AgentFileService.UploadedFileEntry> files) {}