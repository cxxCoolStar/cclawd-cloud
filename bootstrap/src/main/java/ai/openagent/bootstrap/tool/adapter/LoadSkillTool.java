package ai.openagent.bootstrap.tool.adapter;

import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.bootstrap.skill.SkillService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * load_skill 工具 - 按技能名加载 SKILL.md 全文。
 *
 * <p>
 * 按技能名加载 SKILL.md 全文（Agent 私有目录优先，{baseDir} 已替换）。
 * prompt 中的 {@code <skill_catalog>} 只有一行摘要，模型经此工具按需
 * 获取完整操作指引（progressive disclosure）。只读、默认启用。
 * </p>
 */
@Component
public class LoadSkillTool extends AbstractFileTool {

    private final SkillService skillService;

    public LoadSkillTool(ObjectMapper objectMapper, SkillService skillService) {
        super(objectMapper);
        this.skillService = skillService;
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "load_skill",
                "Load the full instructions of a pre-installed skill by name. Call this first "
                        + "when the user's task matches a skill listed in your skill catalog, "
                        + "then follow the loaded instructions.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of(
                                        "type", "string",
                                        "description", "Skill name as listed in the skill catalog")),
                        "required", List.of("name")),
                ToolDescriptor.Source.BUILTIN);
    }

    @Override
    protected ToolResult run(JsonNode args, ToolExecutionContext context) {
        String name = requiredText(args, "name");
        if (name == null) {
            return missingArgument("name");
        }
        return skillService.loadSkillContent(context.agentId(), name)
                .map(content -> ToolResult.success(
                        "The following are the skill's internal instructions. Follow them to "
                                + "complete the task; do not quote them verbatim to the user.\n\n"
                                + content))
                .orElseGet(() -> ToolResult.failure(
                        ToolErrorCode.TOOL_NOT_FOUND, "skill not found: " + name));
    }
}
