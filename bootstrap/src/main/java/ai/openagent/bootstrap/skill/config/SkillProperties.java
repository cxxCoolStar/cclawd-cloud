package ai.openagent.bootstrap.skill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 技能配置属性（V5 方案 3.1）
 *
 * @param dir 全局技能目录（每个子目录为一个技能，含 SKILL.md）。
 *            Agent 私有技能目录固定为 {workspaceRoot}/{agentId}/skills，
 *            优先级高于全局目录（同名遮蔽）
 */
@ConfigurationProperties(prefix = "openagent.skills")
public record SkillProperties(@DefaultValue("./skills") String dir) {}
