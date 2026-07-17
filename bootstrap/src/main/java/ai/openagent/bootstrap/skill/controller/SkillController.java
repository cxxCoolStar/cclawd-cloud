package ai.openagent.bootstrap.skill.controller;

import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.skill.SkillService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 技能管理接口（V5 方案 3.5）
 *
 * <p>
 * 对齐前端 api.ts：GET 列表返回 SkillInfo 数组（不包裹）；
 * ZIP 上传 multipart 字段 file，可选 name 与 ?agent=<id>
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;
    private final AgentService agentService;

    /**
     * 全局技能列表
     */
    @GetMapping("/api/skills")
    public List<SkillService.SkillInfo> listGlobalSkills() {
        return skillService.listGlobal();
    }

    /**
     * Agent 私有技能列表
     */
    @GetMapping("/api/agents/{agentId}/skills")
    public List<SkillService.SkillInfo> listAgentSkills(@PathVariable String agentId) {
        agentService.getAgent(agentId);
        return skillService.listAgentSkills(agentId);
    }

    /**
     * 删除全局技能
     */
    @DeleteMapping("/api/skills/{name}")
    public Map<String, Boolean> deleteGlobalSkill(@PathVariable String name) {
        skillService.deleteSkill(null, name);
        return Map.of("ok", true);
    }

    /**
     * 删除 Agent 私有技能
     */
    @DeleteMapping("/api/agents/{agentId}/skills/{name}")
    public Map<String, Boolean> deleteAgentSkill(@PathVariable String agentId, @PathVariable String name) {
        agentService.getAgent(agentId);
        skillService.deleteSkill(agentId, name);
        return Map.of("ok", true);
    }

    /**
     * ZIP 上传安装（?agent=<id> 时安装到 Agent 私有目录）
     */
    @PostMapping("/api/skills/upload")
    public Map<String, Object> uploadSkill(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String agent) throws IOException {
        if (agent != null && !agent.isBlank()) {
            agentService.getAgent(agent);
        }
        String filename = file.getOriginalFilename() == null ? "skill.zip" : file.getOriginalFilename();
        SkillService.InstallResult result = skillService.installZip(
                agent == null || agent.isBlank() ? null : agent, name, filename, file.getInputStream());
        return Map.of(
                "ok", true,
                "source", "upload",
                "name", result.name(),
                "installedAt", result.installedAt(),
                "files", result.files());
    }
}
