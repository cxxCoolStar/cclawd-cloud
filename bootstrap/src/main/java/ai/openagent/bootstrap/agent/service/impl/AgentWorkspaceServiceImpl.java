package ai.openagent.bootstrap.agent.service.impl;

import ai.openagent.bootstrap.agent.controller.vo.UploadedFilesVO;
import ai.openagent.bootstrap.agent.controller.vo.WorkspaceFilesVO;
import ai.openagent.bootstrap.agent.service.AgentFileService;
import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.agent.service.AgentWorkspaceService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class AgentWorkspaceServiceImpl implements AgentWorkspaceService {

    private final AgentFileService agentFileService;
    private final AgentService agentService;

    @Override
    public WorkspaceFilesVO list(String agentId, String sessionId) {
        agentService.requireAccess(agentId);
        return new WorkspaceFilesVO(agentFileService.listFiles(agentId, sessionId));
    }

    @Override
    public UploadedFilesVO upload(String agentId, String sessionId, List<MultipartFile> files) throws IOException {
        agentService.requireAccess(agentId);
        List<AgentFileService.UploadFile> uploads = new ArrayList<>();
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename();
            if (filename != null && !filename.isBlank()) {
                uploads.add(new AgentFileService.UploadFile(filename, file.getBytes()));
            }
        }
        return new UploadedFilesVO(agentFileService.saveUploads(agentId, sessionId, uploads));
    }

    @Override
    public Path resolve(String agentId, String relativePath) {
        agentService.requireAccess(agentId);
        return agentFileService.resolveFile(agentId, relativePath);
    }
}