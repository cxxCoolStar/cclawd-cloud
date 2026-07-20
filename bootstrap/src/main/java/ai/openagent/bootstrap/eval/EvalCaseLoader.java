package ai.openagent.bootstrap.eval;

import ai.openagent.agent.eval.EvalCase;
import ai.openagent.agent.eval.EvalExpected;
import ai.openagent.agent.eval.EvalExpected.ConstraintExpected;
import ai.openagent.agent.eval.EvalExpected.MaxLimits;
import ai.openagent.agent.eval.EvalExpected.OutcomeExpected;
import ai.openagent.agent.eval.EvalExpected.OutputExpected;
import ai.openagent.agent.eval.EvalExpected.ToolExpected;
import ai.openagent.agent.eval.EvalFixture;
import ai.openagent.agent.eval.EvalScoring;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Eval 测试用例加载器
 * 从 YAML 文件加载测试用例
 */
@Slf4j
@Component
public class EvalCaseLoader {

    private final Yaml yaml;
    private final PathMatchingResourcePatternResolver resolver;

    @Value("${openagent.eval.cases.path:eval/cases}")
    private String casesPath;

    public EvalCaseLoader() {
        this.yaml = new Yaml();
        this.resolver = new PathMatchingResourcePatternResolver();
    }

    /**
     * 加载指定路径下的所有 YAML 用例
     *
     * @param path 路径（支持 classpath: 或文件系统路径）
     * @return 测试用例列表
     */
    public List<EvalCase> loadAll(String path) {
        log.info("Loading eval cases from: {}", path);
        List<EvalCase> cases = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            Resource[] resources = resolver.getResources(getPattern(path));
            for (Resource resource : resources) {
                if (resource.exists() && resource.isReadable()) {
                    try (InputStream is = resource.getInputStream()) {
                        Map<String, Object> map = yaml.load(is);
                        if (map != null) {
                            EvalCase evalCase = convertToEvalCase(map);
                            evalCase.validate();
                            cases.add(evalCase);
                            log.debug("Loaded eval case: {} - {}", evalCase.getId(), evalCase.getName());
                        }
                    } catch (Exception e) {
                        log.error("Failed to load eval case from {}: {}", resource.getFilename(), e.getMessage());
                        errors.add(resource.getFilename() + ": " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to load eval cases from {}: {}", path, e.getMessage());
            throw new RuntimeException("Failed to load eval cases from " + path, e);
        }

        // 如果有加载错误，抛出异常使测试失败，避免静默跳过文件
        if (!errors.isEmpty()) {
            throw new RuntimeException("Failed to load " + errors.size() + " eval case(s): " + errors);
        }

        log.info("Loaded {} eval cases from {}", cases.size(), path);
        return cases;
    }

    /**
     * 已知字段白名单：防止用例里写了不被解析的断言字段（如拼错的 key）
     * 静默失效——"以为在测，其实没测"比测试失败更危险
     */
    private static final Set<String> KNOWN_CASE_KEYS = Set.of(
            "id", "name", "category", "priority", "input", "notes",
            "expected", "scoring", "fixtures", "extensions");
    private static final Set<String> KNOWN_EXPECTED_KEYS = Set.of(
            "tools", "output", "outcome", "constraints", "max");
    private static final Set<String> KNOWN_TOOLS_KEYS = Set.of(
            "required", "forbidden", "ordered", "tool_repetition_max");
    private static final Set<String> KNOWN_OUTPUT_KEYS = Set.of(
            "must_contain", "must_contain_any", "forbidden", "semantic_match");
    private static final Set<String> KNOWN_OUTCOME_KEYS = Set.of(
            "file_exists", "file_content_contains", "file_content_not_contains", "dir_exists");
    private static final Set<String> KNOWN_CONSTRAINTS_KEYS = Set.of("max_iterations");
    private static final Set<String> KNOWN_MAX_KEYS = Set.of(
            "tool_calls", "latency_ms", "total_tokens");
    private static final Set<String> KNOWN_SCORING_KEYS = Set.of(
            "mode", "max_score", "pass_threshold", "result_incorrect_penalty",
            "process_violation_penalty", "efficiency_bonus", "efficiency_penalty_per_extra_call");
    private static final Set<String> KNOWN_FIXTURES_KEYS = Set.of(
            "files", "memory", "skills");

    /**
     * 将 YAML Map 转换为 EvalCase
     */
    @SuppressWarnings("unchecked")
    private EvalCase convertToEvalCase(Map<String, Object> map) {
        rejectUnknownKeys(map, KNOWN_CASE_KEYS, "case");
        EvalCase evalCase = new EvalCase();
        evalCase.setId(getString(map, "id"));
        evalCase.setName(getString(map, "name"));
        evalCase.setCategory(getString(map, "category"));
        evalCase.setPriority(getString(map, "priority"));
        evalCase.setInput(getString(map, "input"));
        evalCase.setNotes(getString(map, "notes"));

        // expected
        Map<String, Object> expectedMap = (Map<String, Object>) map.get("expected");
        if (expectedMap != null) {
            evalCase.setExpected(convertToExpected(expectedMap));
        }

        // scoring
        Map<String, Object> scoringMap = (Map<String, Object>) map.get("scoring");
        if (scoringMap != null) {
            evalCase.setScoring(convertToScoring(scoringMap));
        }

        // fixtures
        Map<String, Object> fixturesMap = (Map<String, Object>) map.get("fixtures");
        if (fixturesMap != null) {
            evalCase.setFixtures(convertToFixtures(fixturesMap));
        }

        return evalCase;
    }

    @SuppressWarnings("unchecked")
    private EvalExpected convertToExpected(Map<String, Object> map) {
        rejectUnknownKeys(map, KNOWN_EXPECTED_KEYS, "expected");
        EvalExpected expected = new EvalExpected();

        // tools
        Map<String, Object> toolsMap = (Map<String, Object>) map.get("tools");
        if (toolsMap != null) {
            rejectUnknownKeys(toolsMap, KNOWN_TOOLS_KEYS, "expected.tools");
            ToolExpected tools = new ToolExpected();
            tools.setRequired(getStringList(toolsMap, "required"));
            tools.setForbidden(getStringList(toolsMap, "forbidden"));
            tools.setOrdered(getBoolean(toolsMap, "ordered", false));
            tools.setToolRepetitionMax(getInteger(toolsMap, "tool_repetition_max"));
            expected.setTools(tools);
        }

        // output
        Map<String, Object> outputMap = (Map<String, Object>) map.get("output");
        if (outputMap != null) {
            rejectUnknownKeys(outputMap, KNOWN_OUTPUT_KEYS, "expected.output");
            OutputExpected output = new OutputExpected();
            output.setMustContain(getStringList(outputMap, "must_contain"));
            output.setMustContainAny(getStringList(outputMap, "must_contain_any"));
            output.setForbidden(getStringList(outputMap, "forbidden"));
            output.setSemanticMatch(getBoolean(outputMap, "semantic_match", true));
            expected.setOutput(output);
        }

        // outcome
        Map<String, Object> outcomeMap = (Map<String, Object>) map.get("outcome");
        if (outcomeMap != null) {
            rejectUnknownKeys(outcomeMap, KNOWN_OUTCOME_KEYS, "expected.outcome");
            OutcomeExpected outcome = new OutcomeExpected();
            outcome.setFileExists(getString(outcomeMap, "file_exists"));
            outcome.setFileContentContains(getString(outcomeMap, "file_content_contains"));
            outcome.setFileContentNotContains(getString(outcomeMap, "file_content_not_contains"));
            outcome.setDirExists(getString(outcomeMap, "dir_exists"));
            expected.setOutcome(outcome);
        }

        // constraints
        Map<String, Object> constraintsMap = (Map<String, Object>) map.get("constraints");
        if (constraintsMap != null) {
            rejectUnknownKeys(constraintsMap, KNOWN_CONSTRAINTS_KEYS, "expected.constraints");
            ConstraintExpected constraints = new ConstraintExpected();
            constraints.setMaxIterations(getInteger(constraintsMap, "max_iterations"));
            expected.setConstraints(constraints);
        }

        // max
        Map<String, Object> maxMap = (Map<String, Object>) map.get("max");
        if (maxMap != null) {
            rejectUnknownKeys(maxMap, KNOWN_MAX_KEYS, "expected.max");
            MaxLimits max = new MaxLimits();
            max.setToolCalls(getInteger(maxMap, "tool_calls"));
            max.setLatencyMs(getLong(maxMap, "latency_ms"));
            max.setTotalTokens(getInteger(maxMap, "total_tokens"));
            expected.setMax(max);
        }

        return expected;
    }

    @SuppressWarnings("unchecked")
    private EvalScoring convertToScoring(Map<String, Object> map) {
        rejectUnknownKeys(map, KNOWN_SCORING_KEYS, "scoring");
        EvalScoring scoring = new EvalScoring();
        scoring.setMode(getString(map, "mode", "deduction"));
        scoring.setMaxScore(getInteger(map, "max_score", 100));
        scoring.setPassThreshold(getInteger(map, "pass_threshold", 80));
        scoring.setResultIncorrectPenalty(getInteger(map, "result_incorrect_penalty", 100));
        scoring.setProcessViolationPenalty(getInteger(map, "process_violation_penalty", 10));
        scoring.setEfficiencyBonus(getBoolean(map, "efficiency_bonus", false));
        scoring.setEfficiencyPenaltyPerExtraCall(getInteger(map, "efficiency_penalty_per_extra_call", 10));
        return scoring;
    }

    @SuppressWarnings("unchecked")
    private EvalFixture convertToFixtures(Map<String, Object> map) {
        rejectUnknownKeys(map, KNOWN_FIXTURES_KEYS, "fixtures");
        EvalFixture fixtures = new EvalFixture();

        // files
        List<Map<String, Object>> filesList = (List<Map<String, Object>>) map.get("files");
        if (filesList != null) {
            List<EvalFixture.FileFixture> files = new ArrayList<>();
            for (Map<String, Object> fileMap : filesList) {
                EvalFixture.FileFixture file = new EvalFixture.FileFixture();
                file.setPath(getString(fileMap, "path"));
                file.setContent(getString(fileMap, "content"));
                files.add(file);
            }
            fixtures.setFiles(files);
        }

        // memory
        List<String> memoryList = (List<String>) map.get("memory");
        if (memoryList != null) {
            fixtures.setMemory(memoryList);
        }

        // skills
        List<Map<String, Object>> skillsList = (List<Map<String, Object>>) map.get("skills");
        if (skillsList != null) {
            List<EvalFixture.SkillFixture> skills = new ArrayList<>();
            for (Map<String, Object> skillMap : skillsList) {
                EvalFixture.SkillFixture skill = new EvalFixture.SkillFixture();
                skill.setName(getString(skillMap, "name"));
                skill.setTrigger(getString(skillMap, "trigger"));
                skills.add(skill);
            }
            fixtures.setSkills(skills);
        }

        return fixtures;
    }

    /**
     * 未知字段直接抛错，由 loadAll 汇总为加载失败
     */
    private void rejectUnknownKeys(Map<String, Object> map, Set<String> knownKeys, String section) {
        List<String> unknown = map.keySet().stream()
                .filter(k -> !knownKeys.contains(k))
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown key(s) in " + section + ": " + unknown + " (known: " + knownKeys + ")");
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List) {
            return (List<String>) value;
        }
        return Collections.emptyList();
    }

    private Integer getInteger(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    private Integer getInteger(Map<String, Object> map, String key, int defaultValue) {
        Integer value = getInteger(map, key);
        return value != null ? value : defaultValue;
    }

    private Long getLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    /**
     * 加载所有默认路径的测试用例
     */
    public List<EvalCase> loadAll() {
        return loadAll(casesPath);
    }

    /**
     * 加载单个用例
     *
     * @param caseId 用例 ID
     * @return 测试用例，未找到返回 null
     */
    public EvalCase load(String caseId) {
        List<EvalCase> cases = loadAll();
        return cases.stream()
                .filter(c -> caseId.equals(c.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 从文件系统路径加载单个 YAML 文件
     *
     * @param filePath 文件路径
     * @return 测试用例
     */
    @SuppressWarnings("unchecked")
    public EvalCase loadFromFile(Path filePath) {
        try (InputStream is = Files.newInputStream(filePath)) {
            Map<String, Object> map = yaml.load(is);
            if (map != null) {
                EvalCase evalCase = convertToEvalCase(map);
                evalCase.validate();
                return evalCase;
            }
            return null;
        } catch (IOException e) {
            log.error("Failed to load eval case from {}: {}", filePath, e.getMessage());
            throw new RuntimeException("Failed to load eval case: " + filePath, e);
        }
    }

    /**
     * 将路径转换为资源匹配模式
     */
    private String getPattern(String path) {
        if (path.startsWith("classpath:")) {
            return path + "/**/*.yaml";
        }
        // 文件系统路径
        return "file:" + path + "/**/*.yaml";
    }

    @PostConstruct
    public void init() {
        log.info("EvalCaseLoader initialized with cases path: {}", casesPath);
    }
}
