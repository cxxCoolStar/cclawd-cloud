package ai.openagent.bootstrap.skill.service.impl;

import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.skill.SkillService;
import ai.openagent.bootstrap.skill.controller.vo.SkillUploadVO;
import ai.openagent.bootstrap.skill.service.SkillManagementService;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class SkillManagementServiceImpl implements SkillManagementService {

    private final SkillService skillService;
    private final AgentService agentService;

    @Override
    public List<SkillService.SkillInfo> listGlobal() {
        return skillService.listGlobal();
    }

    @Override
    public List<SkillService.SkillInfo> listAgent(String agentId) {
        agentService.requireAccess(agentId);
        return skillService.listAgentSkills(agentId);
    }

    @Override
    public void deleteGlobal(String name) {
        skillService.deleteSkill(null, name);
    }

    @Override
    public void deleteAgent(String agentId, String name) {
        agentService.requireAccess(agentId);
        skillService.deleteSkill(agentId, name);
    }

    @Override
    public SkillUploadVO upload(MultipartFile file, String name, String agentId) throws IOException {
        String normalizedAgentId = normalize(agentId);
        if (normalizedAgentId != null) {
            agentService.requireAccess(normalizedAgentId);
        }
        String filename = file.getOriginalFilename() == null ? "skill.zip" : file.getOriginalFilename();
        try (InputStream inputStream = file.getInputStream()) {
            SkillService.InstallResult result =
                    skillService.installZip(normalizedAgentId, name, filename, inputStream);
            return new SkillUploadVO("upload", result.name(), result.installedAt(), result.files());
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}