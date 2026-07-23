package ai.openagent.bootstrap.agent.service;

import ai.openagent.bootstrap.agent.controller.vo.UploadedFilesVO;
import ai.openagent.bootstrap.agent.controller.vo.WorkspaceFilesVO;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface AgentWorkspaceService {

    WorkspaceFilesVO list(String agentId, String sessionId);

    UploadedFilesVO upload(String agentId, String sessionId, List<MultipartFile> files) throws IOException;

    Path resolve(String agentId, String relativePath);
}