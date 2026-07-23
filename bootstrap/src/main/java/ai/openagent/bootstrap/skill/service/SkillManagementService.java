package ai.openagent.bootstrap.skill.service;

import ai.openagent.bootstrap.skill.SkillService;
import ai.openagent.bootstrap.skill.controller.vo.SkillUploadVO;
import java.io.IOException;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface SkillManagementService {

    List<SkillService.SkillInfo> listGlobal();

    List<SkillService.SkillInfo> listAgent(String agentId);

    void deleteGlobal(String name);

    void deleteAgent(String agentId, String name);

    SkillUploadVO upload(MultipartFile file, String name, String agentId) throws IOException;
}