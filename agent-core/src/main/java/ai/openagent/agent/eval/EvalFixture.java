package ai.openagent.agent.eval;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 测试夹具（前置条件定义）
 */
@Data
public class EvalFixture {

    /**
     * 文件夹具列表
     */
    private List<FileFixture> files = Collections.emptyList();

    /**
     * 记忆夹具列表
     */
    private List<String> memory = Collections.emptyList();

    /**
     * 技能夹具列表
     */
    private List<SkillFixture> skills = Collections.emptyList();

    /**
     * 文件夹具定义
     */
    @Data
    public static class FileFixture {
        /**
         * 文件路径（相对于 workspace）
         */
        private String path;

        /**
         * 文件内容
         */
        private String content;
    }

    /**
     * 技能夹具定义
     */
    @Data
    public static class SkillFixture {
        /**
         * 技能名称
         */
        private String name;

        /**
         * 技能触发词
         */
        private String trigger;
        /** SKILL.md body for an eval-only skill fixture. */
        private String content;
    }
}
