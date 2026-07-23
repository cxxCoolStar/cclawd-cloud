package ai.openagent.bootstrap.skill.controller;

import ai.openagent.bootstrap.skill.service.SkillManagementService;
import ai.openagent.bootstrap.skill.SkillService;
import ai.openagent.bootstrap.skill.controller.vo.SkillUploadVO;
import ai.openagent.framework.convention.Result;
import ai.openagent.framework.web.Results;
import java.io.IOException;
import java.util.List;
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

    private final SkillManagementService skillManagementService;

    /**
     * 全局技能列表
     */
    @GetMapping("/api/skills")
    public List<SkillService.SkillInfo> listGlobalSkills() {
        return skillManagementService.listGlobal();
    }

    /**
     * Agent 私有技能列表
     */
    @GetMapping("/api/agents/{agentId}/skills")
    public List<SkillService.SkillInfo> listAgentSkills(@PathVariable String agentId) {
        return skillManagementService.listAgent(agentId);
    }

    /**
     * 删除全局技能
     */
    @DeleteMapping("/api/skills/{name}")
    public Result<Void> deleteGlobalSkill(@PathVariable String name) {
        skillManagementService.deleteGlobal(name);
        return Results.success();
    }

    /**
     * 删除 Agent 私有技能
     */
    @DeleteMapping("/api/agents/{agentId}/skills/{name}")
    public Result<Void> deleteAgentSkill(@PathVariable String agentId, @PathVariable String name) {
        skillManagementService.deleteAgent(agentId, name);
        return Results.success();
    }

    /**
     * ZIP 上传安装（?agent=<id> 时安装到 Agent 私有目录）
     */
    @PostMapping("/api/skills/upload")
    public Result<SkillUploadVO> uploadSkill(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String agent) throws IOException {
        return Results.success(skillManagementService.upload(file, name, agent));
    }
}