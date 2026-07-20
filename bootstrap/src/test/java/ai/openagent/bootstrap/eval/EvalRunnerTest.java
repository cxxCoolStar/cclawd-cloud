package ai.openagent.bootstrap.eval;

import ai.openagent.agent.AgentEvent;
import ai.openagent.agent.AgentEventSink;
import ai.openagent.agent.AgentKernel;
import ai.openagent.agent.AgentRunCommand;
import ai.openagent.agent.AgentRunResult;
import ai.openagent.agent.AgentRunStatus;
import ai.openagent.agent.AgentRuntimeConfig;
import ai.openagent.agent.eval.EvalCase;
import ai.openagent.agent.eval.EvalContext;
import ai.openagent.agent.eval.EvalReport;
import ai.openagent.agent.eval.EvalReport.CaseResult;
import ai.openagent.agent.eval.EvalReport.Deduction;
import ai.openagent.agent.eval.Grader;
import ai.openagent.agent.eval.GraderResult;
import ai.openagent.agent.eval.grader.LatencyBudgetGrader;
import ai.openagent.agent.eval.grader.OutcomeStateGrader;
import ai.openagent.agent.eval.grader.OutputContentGrader;
import ai.openagent.agent.eval.grader.TokenBudgetGrader;
import ai.openagent.agent.eval.grader.ToolContractGrader;
import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.agentrun.config.AgentProperties;
import ai.openagent.bootstrap.agentrun.trace.TraceService;
import ai.openagent.bootstrap.agentrun.trace.TraceVO;
import ai.openagent.bootstrap.persistence.AgentRunRecord;
import ai.openagent.bootstrap.persistence.AgentRunRepository;
import ai.openagent.bootstrap.persistence.AgentToolRepository;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Eval 运行器测试
 * 使用独立 profile（eval）运行，隔离数据库
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("eval")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EvalRunnerTest {

    @Autowired
    private EvalCaseLoader caseLoader;

    @Autowired
    private EvalWorkspaceManager workspaceManager;

    @Autowired
    private AgentKernel agentKernel;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentService agentService;

    @Autowired
    private AgentToolRepository agentToolRepository;

    @Autowired
    private TraceService traceService;

    @Autowired
    private AgentProperties agentProperties;

    @Autowired
    private ToolProperties toolProperties;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    // 评分器列表
    private final List<Grader> graders = List.of(
            new ToolContractGrader(),
            new OutputContentGrader(),
            new LatencyBudgetGrader(),
            new TokenBudgetGrader(),
            new OutcomeStateGrader()
    );

    // 使用 DataSeeder 创建的默认 agent
    private static final String DEFAULT_AGENT_ID = "default";
    private static final String EVAL_USER_ID = "eval-user";

    /**
     * 运行单个用例（用于调试）
     * 通过 -DcaseId=xxx 指定用例
     */
    @Test
    @Order(1)
    void runSingleCase() {
        String caseId = System.getProperty("caseId");
        if (caseId == null || caseId.isBlank()) {
            caseId = "file-read-basic"; // 默认用例
        }

        log.info("Running single eval case: {}", caseId);

        EvalCase evalCase = caseLoader.load(caseId);
        assertNotNull(evalCase, "Eval case not found: " + caseId);

        CaseResult result = executeAndGrade(evalCase);

        log.info("Case result: id={}, passed={}, score={}",
                result.caseId(), result.passed(), result.score());

        // 生成报告
        generateReport(List.of(result));

        assertTrue(result.passed(),
                "Case " + caseId + " failed: " + result.deductions());
    }

    /**
     * 运行所有用例
     */
    @Test
    @Order(2)
    void runAllCases() {
        log.info("Running all eval cases");

        List<EvalCase> cases = caseLoader.loadAll();
        assertFalse(cases.isEmpty(), "No eval cases found");

        List<CaseResult> results = new ArrayList<>();
        int caseIndex = 0;
        for (EvalCase evalCase : cases) {
            caseIndex++;
            try {
                CaseResult result = executeAndGrade(evalCase);
                results.add(result);
                log.info("Case completed: id={}, passed={}, score={}",
                        result.caseId(), result.passed(), result.score());
            } catch (Exception e) {
                log.error("Case failed with exception: {}", evalCase.getId(), e);
                results.add(new CaseResult(
                        evalCase.getId(),
                        evalCase.getName(),
                        evalCase.getCategory(),
                        false,
                        0,
                        List.of(new Deduction("EXCEPTION", 100, e.getMessage())),
                        List.of(e.toString()),
                        0,
                        null,
                        null));
            }
            // Rate limit 保护：每个用例之间等待 10 秒（除了最后一个）
            // 腾讯云 kimi-k2.5 有严格的 TPM 限制，需要更长的间隔
            if (caseIndex < cases.size()) {
                try {
                    log.debug("Rate limit protection: waiting 10s before next case...");
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Rate limit wait interrupted");
                    break;
                }
            }
        }

        // 生成报告
        EvalReport report = generateReport(results);

        log.info("Eval summary: total={}, passed={}, passRate={}%, meanScore={}",
                report.totalCases(), report.passedCases(),
                String.format("%.1f", report.passRate()),
                String.format("%.1f", report.meanScore()));

        // 可以添加阈值检查
        // assertTrue(report.passRate() >= 80, "Pass rate below threshold");
    }

    /**
     * 执行单个用例并评分
     */
    private CaseResult executeAndGrade(EvalCase evalCase) {
        String runId = "eval-" + UUID.randomUUID().toString().substring(0, 8);
        Instant startTime = Instant.now();

        log.info("Executing case: id={}, runId={}", evalCase.getId(), runId);

        // 1. 创建工作空间
        Path runWorkspace = workspaceManager.createRunWorkspace(runId);
        Path workspaceDir = workspaceManager.getWorkspaceDir(runId);

        // 2. 创建夹具（传入 agentId 以支持 memory fixtures）
        if (evalCase.getFixtures() != null) {
            workspaceManager.createFixtures(runId, DEFAULT_AGENT_ID, evalCase.getFixtures());
        }

        // 3. 启用用例所需的工具
        enableRequiredTools(evalCase);

        // 4. 运行 Agent
        AgentRunResult runResult;
        String finalOutput;
        String finalReasoning;
        List<EvalContext.ToolCall> toolCalls;
        EvalContext.TokenUsage tokenUsage;
        Instant endTime;

        try {
            RunResult result = runAgent(evalCase, runId, workspaceDir.toString());
            runResult = result.runResult();
            finalOutput = result.output();
            finalReasoning = result.reasoning();
            toolCalls = result.toolCalls();
            tokenUsage = result.tokenUsage();
            endTime = Instant.now();
        } catch (Exception e) {
            endTime = Instant.now();
            long durationMs = Duration.between(startTime, endTime).toMillis();

            // 清理工作空间
            workspaceManager.cleanupRunWorkspace(runId);

            return new CaseResult(
                    evalCase.getId(),
                    evalCase.getName(),
                    evalCase.getCategory(),
                    false,
                    0,
                    List.of(new Deduction("EXECUTION", 100, "Execution failed: " + e.getMessage())),
                    List.of(e.toString()),
                    durationMs,
                    null,
                    null);
        }

        long durationMs = Duration.between(startTime, endTime).toMillis();

        // 4. 构建 EvalContext
        EvalContext context = EvalContext.builder()
                .runResult(runResult)
                .output(finalOutput)
                .toolCalls(toolCalls)
                .tokenUsage(tokenUsage)
                .startTime(startTime)
                .endTime(endTime)
                .latencyMs(durationMs)
                .workspacePath(workspaceDir.toString())
                .build();

        // 5. 评分
        List<Deduction> deductions = new ArrayList<>();
        int totalDeduction = 0;
        List<String> allEvidence = new ArrayList<>();

        int maxScore = evalCase.getScoring() != null ? evalCase.getScoring().getMaxScore() : 100;
        
        for (Grader grader : graders) {
            try {
                GraderResult result = grader.grade(evalCase, context);
                if (!result.passed()) {
                    deductions.add(new Deduction(
                            grader.getClass().getSimpleName(),
                            result.deduction(),
                            result.reason()));
                    totalDeduction += result.deduction();
                }
                allEvidence.addAll(result.evidence());
            } catch (Exception e) {
                log.error("Grader failed: {}", grader.getClass().getSimpleName(), e);
                // Grader 崩溃应视为严重错误，扣除全部分数
                deductions.add(new Deduction(
                        grader.getClass().getSimpleName(),
                        maxScore,
                        "Grader error: " + e.getMessage()));
                totalDeduction += maxScore;
            }
        }

        // 计算最终分数
        int score = Math.max(0, maxScore - totalDeduction);

        // 如果运行状态是失败，直接 0 分
        if (runResult.status() == AgentRunStatus.FAILED
                || runResult.status() == AgentRunStatus.TIMED_OUT
                || runResult.status() == AgentRunStatus.INTERRUPTED) {
            score = 0;
            deductions.add(new Deduction("STATUS", maxScore,
                    "Run failed with status: " + runResult.status()));
        }

        int passThreshold = evalCase.getScoring() != null ? evalCase.getScoring().getPassThreshold() : 80;
        boolean passed = score >= passThreshold;

        // 6. 清理工作空间
        workspaceManager.cleanupRunWorkspace(runId);

        return new CaseResult(
                evalCase.getId(),
                evalCase.getName(),
                evalCase.getCategory(),
                passed,
                score,
                deductions,
                allEvidence,
                durationMs,
                finalReasoning,
                finalOutput);
    }

    /**
     * 启用用例所需的工具
     * 根据用例的 expected.tools.required 自动启用相应工具
     */
    private void enableRequiredTools(EvalCase evalCase) {
        if (evalCase.getExpected() == null || evalCase.getExpected().getTools() == null) {
            return;
        }
        
        List<String> requiredTools = evalCase.getExpected().getTools().getRequired();
        if (requiredTools == null || requiredTools.isEmpty()) {
            return;
        }
        
        for (String toolName : requiredTools) {
            try {
                // Upsert 工具配置（启用状态）
                agentToolRepository.upsert(DEFAULT_AGENT_ID, toolName, true, "{}");
                log.debug("Enabled tool {} for eval case {}", toolName, evalCase.getId());
            } catch (Exception e) {
                log.warn("Failed to enable tool {} for eval case {}: {}", toolName, evalCase.getId(), e.getMessage());
            }
        }
    }

    /**
     * 运行 Agent
     */
    private RunResult runAgent(EvalCase evalCase, String runId, String workspacePath) throws Exception {
        String sessionId = "eval-" + runId;

        // 构建 AgentRunCommand
        AgentRuntimeConfig runtimeConfig = new AgentRuntimeConfig(
                agentProperties.maxToolIterations(),
                agentProperties.runTimeout(),
                toolProperties.executionTimeout());

        AgentRunCommand command = new AgentRunCommand(
                runId,
                EVAL_USER_ID,
                DEFAULT_AGENT_ID,
                sessionId,
                evalCase.getInput(),
                runtimeConfig,
                Path.of(workspacePath));

        // 收集事件和输出
        StringBuilder outputBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        List<EvalContext.ToolCall> toolCalls = new ArrayList<>();
        CountDownLatch doneLatch = new CountDownLatch(1);
        Map<String, PendingToolCall> pendingToolCalls = new ConcurrentHashMap<>();

        AgentEventSink eventSink = event -> {
            if (event instanceof AgentEvent.ContentDelta) {
                outputBuilder.append(((AgentEvent.ContentDelta) event).delta());
            } else if (event instanceof AgentEvent.ReasoningDelta) {
                reasoningBuilder.append(((AgentEvent.ReasoningDelta) event).delta());
            } else if (event instanceof AgentEvent.Content) {
                AgentEvent.Content content = (AgentEvent.Content) event;
                if (content.content() != null) {
                    outputBuilder.append(content.content());
                }
            } else if (event instanceof AgentEvent.ToolCallRequested) {
                AgentEvent.ToolCallRequested req = (AgentEvent.ToolCallRequested) event;
                PendingToolCall pending = new PendingToolCall(req.name(), req.arguments(), toolCalls.size() + 1);
                pendingToolCalls.put(req.id(), pending);
            } else if (event instanceof AgentEvent.ToolResultProduced) {
                AgentEvent.ToolResultProduced result = (AgentEvent.ToolResultProduced) event;
                PendingToolCall pending = pendingToolCalls.remove(result.id());
                if (pending != null) {
                    EvalContext.ToolCall toolCall = EvalContext.ToolCall.builder()
                            .toolName(pending.toolName)
                            .arguments(pending.arguments)
                            .result(result.result())
                            .sequence(pending.sequence)
                            .build();
                    toolCalls.add(toolCall);
                }
            } else if (event instanceof AgentEvent.Done) {
                doneLatch.countDown();
            } else if (event instanceof AgentEvent.RunFailed) {
                AgentEvent.RunFailed failed = (AgentEvent.RunFailed) event;
                log.warn("Run failed: {}", failed.message());
                doneLatch.countDown();
            }
        };

        // 运行 Agent
        AgentRunResult result = agentKernel.run(command, eventSink);

        // 等待完成（最多 5 分钟）
        boolean completed = doneLatch.await(5, TimeUnit.MINUTES);
        if (!completed) {
            log.warn("Run timed out waiting for done event: {}", runId);
        }

        // 获取 token 用量
        EvalContext.TokenUsage tokenUsage = fetchTokenUsage(runId);

        return new RunResult(result, outputBuilder.toString().trim(), reasoningBuilder.toString().trim(), toolCalls, tokenUsage);
    }

    /**
     * 获取 token 用量
     */
    private EvalContext.TokenUsage fetchTokenUsage(String runId) {
        try {
            AgentRunRecord record = runRepository.findById(runId).orElse(null);
            if (record != null) {
                return EvalContext.TokenUsage.builder()
                        .inputTokens((int) record.inputTokens())
                        .outputTokens((int) record.outputTokens())
                        .cacheReadTokens((int) record.cacheReadTokens())
                        .cacheWriteTokens((int) record.cacheWriteTokens())
                        .totalTokens((int) (record.inputTokens() + record.outputTokens()
                                + record.cacheReadTokens() + record.cacheWriteTokens()))
                        .build();
            }
        } catch (Exception e) {
            log.warn("Failed to fetch token usage for {}: {}", runId, e.getMessage());
        }
        return EvalContext.TokenUsage.empty();
    }

    /**
     * 生成报告
     */
    private EvalReport generateReport(List<CaseResult> results) {
        Instant now = Instant.now();
        String runId = "eval-run-" + UUID.randomUUID().toString().substring(0, 8);

        int totalCases = results.size();
        int passedCases = (int) results.stream().filter(CaseResult::passed).count();
        double passRate = totalCases > 0 ? (double) passedCases / totalCases * 100 : 0;
        double meanScore = totalCases > 0
                ? results.stream().mapToInt(CaseResult::score).average().orElse(0)
                : 0;

        EvalReport report = EvalReport.builder()
                .runId(runId)
                .startTime(now)
                .endTime(now)
                .results(results)
                .totalCases(totalCases)
                .passedCases(passedCases)
                .passRate(passRate)
                .meanScore(meanScore)
                .build();

        try {
            Path reportPath = Path.of("target/eval-report.json").toAbsolutePath();
            Files.createDirectories(reportPath.getParent());
            objectMapper.writeValue(reportPath.toFile(), report);
            log.info("Eval report written to: {}", reportPath);
        } catch (Exception e) {
            log.error("Failed to write eval report", e);
        }

        return report;
    }

    private record RunResult(
            AgentRunResult runResult,
            String output,
            String reasoning,
            List<EvalContext.ToolCall> toolCalls,
            EvalContext.TokenUsage tokenUsage) {
    }

    /**
     * 等待中的工具调用
     */
    private record PendingToolCall(
            String toolName,
            String arguments,
            Integer sequence) {
    }
}
