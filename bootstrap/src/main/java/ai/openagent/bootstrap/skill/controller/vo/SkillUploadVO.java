package ai.openagent.bootstrap.skill.controller.vo;

import java.util.List;

public record SkillUploadVO(String source, String name, String installedAt, List<String> files) {}