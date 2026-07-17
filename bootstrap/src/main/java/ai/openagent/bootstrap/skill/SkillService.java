package ai.openagent.bootstrap.skill;

import ai.openagent.bootstrap.skill.config.SkillProperties;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * 技能服务（V5 方案，对照 fastclaw internal/agent/skills.go 与
 * internal/setup/handlers_skills.go）
 *
 * <p>
 * 保留的 fastclaw 行为：技能 = 目录 + SKILL.md（YAML frontmatter +
 * Markdown 正文），目录名是技能 key；全局目录与 Agent 私有目录两层扫描、
 * 同名 Agent 私有遮蔽全局；system prompt 只放 {@code <skill_catalog>}
 * 一行一技能摘要，全文经 load_skill 按需加载；列表 API 形状
 * {name, description, location, type, envSpec}。
 * V5 收缩：不做 gating、alwaysLoad、远程安装与启停/env 配置
 * </p>
 */
@Slf4j
@Service
public class SkillService {

    /**
     * 技能环境变量规格（前端 SkillEnvSpec 形状）
     */
    public record EnvSpec(String name, String description, boolean required, boolean secret) {}

    /**
     * 技能信息（前端 SkillInfo 形状；type 固定 "skill"）
     */
    public record SkillInfo(String name, String description, String location, String type,
                            List<EnvSpec> envSpec) {}

    /**
     * 解析后的技能（运行时视图）
     */
    public record Skill(String name, String description, List<EnvSpec> envSpec, Path directory,
                        String body) {}

    private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z0-9._-]+");
    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\s*\\R(.*?)\\R---\\s*\\R?", Pattern.DOTALL);
    private static final int DESCRIPTION_MAX_CHARS = 140;

    private final SkillProperties skillProperties;
    private final ToolProperties toolProperties;

    public SkillService(SkillProperties skillProperties, ToolProperties toolProperties) {
        this.skillProperties = skillProperties;
        this.toolProperties = toolProperties;
    }

    /**
     * 全局技能目录
     */
    public Path globalDir() {
        return Path.of(skillProperties.dir());
    }

    /**
     * Agent 私有技能目录（最高优先级）
     */
    public Path agentDir(String agentId) {
        return Path.of(toolProperties.workspaceRoot()).resolve(agentId).resolve("skills");
    }

    /**
     * 全局技能列表（GET /api/skills）
     */
    public List<SkillInfo> listGlobal() {
        return scanDir(globalDir());
    }

    /**
     * Agent 私有技能列表（GET /api/agents/{id}/skills）
     */
    public List<SkillInfo> listAgentSkills(String agentId) {
        return scanDir(agentDir(agentId));
    }

    /**
     * 运行时合并视图：全局 + Agent 私有（同名遮蔽），按名字排序
     * （fastclaw LoadSkills 层序与排序语义）
     */
    public List<Skill> loadAll(String agentId) {
        Map<String, Skill> merged = new LinkedHashMap<>();
        for (Skill skill : scan(globalDir())) {
            merged.put(skill.name(), skill);
        }
        for (Skill skill : scan(agentDir(agentId))) {
            merged.put(skill.name(), skill);
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(Skill::name))
                .toList();
    }

    /**
     * 读取技能全文（load_skill 工具）：Agent 私有优先，{baseDir} 替换为
     * 技能目录绝对路径（fastclaw load_skill.go 语义）
     */
    public Optional<String> loadSkillContent(String agentId, String name) {
        validateName(name);
        for (Path dir : List.of(agentDir(agentId), globalDir())) {
            Path skillFile = dir.resolve(name).resolve("SKILL.md");
            if (Files.isRegularFile(skillFile)) {
                String content = readQuietly(skillFile);
                String baseDir = skillFile.getParent().toAbsolutePath().normalize().toString();
                return Optional.of(content.replace("{baseDir}", baseDir));
            }
        }
        return Optional.empty();
    }

    /**
     * system prompt 技能摘要（fastclaw BuildSkillsSummary 对齐）：
     * 无技能时返回空串（不注入段落）
     */
    public String buildSkillsSummary(String agentId) {
        List<Skill> skills = loadAll(agentId);
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        summary.append("<skill_usage_rules>\n")
                .append("Skills are pre-written instruction packs for specific tasks. ")
                .append("When the user's request matches a skill's description, call the ")
                .append("`load_skill` tool with the skill name to load its full instructions ")
                .append("before doing the task, then follow them.\n")
                .append("</skill_usage_rules>\n\n");
        summary.append("<skill_catalog>\n")
                .append("Pre-installed skills available to this agent. Call `load_skill` ")
                .append("with the skill name to load the full instructions.\n");
        for (Skill skill : skills) {
            summary.append("- ").append(skill.name());
            if (!skill.description().isBlank()) {
                summary.append(" — ").append(firstSentence(skill.description()));
            }
            summary.append('\n');
        }
        summary.append("</skill_catalog>");
        return summary.toString();
    }

    /**
     * ZIP 上传安装结果
     */
    public record InstallResult(String name, String installedAt, List<String> files) {}

    /**
     * ZIP 上传安装（fastclaw handleUploadSkill 对齐）：
     * 单顶层目录剥离；根下必须有 SKILL.md；逐条目防 zip-slip；
     * 同名覆盖安装；agentId 非空时安装到 Agent 私有目录
     */
    public InstallResult installZip(String agentId, String providedName, String zipFilename,
                                    java.io.InputStream zipStream) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(zipStream)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                entries.put(name, zip.readAllBytes());
            }
        }
        if (entries.isEmpty()) {
            throw new ClientException("empty zip", BaseErrorCode.PARAM_VERIFY_ERROR);
        }

        // 单顶层目录剥离（fastclaw 同款）
        String prefix = commonTopLevelPrefix(entries.keySet());
        Map<String, byte[]> stripped = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey().substring(prefix.length());
            if (!name.isBlank()) {
                stripped.put(name, entry.getValue());
            }
        }
        if (!stripped.containsKey("SKILL.md")) {
            throw new ClientException("zip must contain SKILL.md at its root",
                    BaseErrorCode.PARAM_VERIFY_ERROR);
        }

        String name = providedName;
        if (name == null || name.isBlank()) {
            name = prefix.isBlank()
                    ? zipFilename.replaceAll("\\.zip$", "")
                    : prefix.substring(0, prefix.length() - 1);
        }
        validateName(name);

        Path targetDir = (agentId == null ? globalDir() : agentDir(agentId)).resolve(name);
        Path targetRoot = targetDir.toAbsolutePath().normalize();
        if (Files.exists(targetDir)) {
            deleteSkill(agentId, name);
        }
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : stripped.entrySet()) {
            Path target = targetRoot.resolve(entry.getKey()).normalize();
            if (!target.startsWith(targetRoot)) {
                throw new ClientException("zip entry escapes target: " + entry.getKey(),
                        BaseErrorCode.PARAM_VERIFY_ERROR);
            }
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
            written.add(entry.getKey());
        }
        log.info("[skill] ZIP 安装完成，agentId={}, name={}, files={}", agentId, name, written.size());
        return new InstallResult(name, targetRoot.toString(), written);
    }

    /**
     * 所有条目共享的单层顶层目录前缀（含结尾 /）；无共享前缀返回空串
     */
    private static String commonTopLevelPrefix(java.util.Set<String> names) {
        String prefix = null;
        for (String name : names) {
            int slash = name.indexOf('/');
            String top = slash < 0 ? "" : name.substring(0, slash + 1);
            if (top.isEmpty()) {
                return "";
            }
            if (prefix == null) {
                prefix = top;
            } else if (!prefix.equals(top)) {
                return "";
            }
        }
        return prefix == null ? "" : prefix;
    }

    /**
     * 删除技能（DELETE 接口；只作用于技能目录，名字先校验）
     */
    public void deleteSkill(String agentId, String name) {
        validateName(name);
        Path dir = agentId == null ? globalDir().resolve(name) : agentDir(agentId).resolve(name);
        if (!Files.isDirectory(dir)) {
            throw new ClientException("skill not found", BaseErrorCode.RESOURCE_NOT_FOUND);
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            });
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        log.info("[skill] 技能已删除，agentId={}, name={}", agentId, name);
    }

    /**
     * 扫描目录下的技能（API 视图）
     */
    private List<SkillInfo> scanDir(Path dir) {
        return scan(dir).stream()
                .map(skill -> new SkillInfo(
                        skill.name(),
                        skill.description(),
                        skill.directory().toAbsolutePath().normalize().toString(),
                        "skill",
                        skill.envSpec()))
                .toList();
    }

    /**
     * 扫描目录：每个含 SKILL.md 的子目录是一个技能
     */
    private List<Skill> scan(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory)
                    .map(child -> parse(child))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparing(Skill::name))
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /**
     * 解析单个技能目录：frontmatter 优先，无 frontmatter 时 description
     * 回退正文首个非 # 行（fastclaw scanSkillsDir 同款回退）；
     * 解析失败容忍（坏 YAML 不阻断扫描）
     */
    static Optional<Skill> parse(Path skillDir) {
        Path skillFile = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillFile)) {
            return Optional.empty();
        }
        String name = skillDir.getFileName().toString();
        String content = readQuietly(skillFile);
        String body = content;
        String description = "";
        List<EnvSpec> envSpec = List.of();
        var matcher = FRONTMATTER.matcher(content);
        if (matcher.find()) {
            body = content.substring(matcher.end());
            try {
                Map<String, Object> yaml = new Yaml().load(matcher.group(1));
                if (yaml != null) {
                    description = stringValue(yaml.get("description"));
                    envSpec = parseEnvSpec(yaml);
                }
            } catch (RuntimeException error) {
                log.warn("[skill] frontmatter 解析失败，按无 frontmatter 处理，dir={}", skillDir, error);
            }
        }
        if (description.isBlank()) {
            description = firstContentLine(body);
        }
        return Optional.of(new Skill(name, description, envSpec, skillDir, body));
    }

    /**
     * env 规格：顶层 env 优先，其次 metadata.fastclaw / metadata.openclaw
     * （fastclaw handlers_skills.go 合并规则）
     */
    @SuppressWarnings("unchecked")
    private static List<EnvSpec> parseEnvSpec(Map<String, Object> yaml) {
        Object env = yaml.get("env");
        if (!(env instanceof List)) {
            Object metadata = yaml.get("metadata");
            if (metadata instanceof Map<?, ?> metadataMap) {
                for (String key : List.of("fastclaw", "openclaw")) {
                    Object section = metadataMap.get(key);
                    if (section instanceof Map<?, ?> sectionMap && sectionMap.get("env") instanceof List) {
                        env = sectionMap.get("env");
                        break;
                    }
                }
            }
        }
        if (!(env instanceof List)) {
            return List.of();
        }
        List<EnvSpec> specs = new ArrayList<>();
        for (Object item : (List<Object>) env) {
            if (item instanceof Map<?, ?> itemMap) {
                String name = stringValue(itemMap.get("name"));
                if (!name.isBlank()) {
                    specs.add(new EnvSpec(
                            name,
                            stringValue(itemMap.get("description")),
                            Boolean.TRUE.equals(itemMap.get("required")),
                            Boolean.TRUE.equals(itemMap.get("secret"))));
                }
            }
        }
        return specs;
    }

    /**
     * 技能名/目录名校验（防路径穿越）
     */
    static void validateName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches() || name.contains("..")) {
            throw new ClientException("invalid skill name", BaseErrorCode.PARAM_VERIFY_ERROR);
        }
    }

    /**
     * 摘要目录行：描述首句，截断 140 字符（fastclaw firstSentence 对齐）
     */
    static String firstSentence(String description) {
        String collapsed = description.replaceAll("\\s+", " ").trim();
        for (String delimiter : new String[] {". ", "。", "! ", "！"}) {
            int index = collapsed.indexOf(delimiter);
            if (index > 0) {
                collapsed = collapsed.substring(0, index + delimiter.length()).trim();
                break;
            }
        }
        return collapsed.length() <= DESCRIPTION_MAX_CHARS
                ? collapsed
                : collapsed.substring(0, DESCRIPTION_MAX_CHARS);
    }

    private static String firstContentLine(String body) {
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return trimmed;
            }
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "";
        }
    }
}
