package ai.openagent.bootstrap.agentrun.trace;

import ai.openagent.bootstrap.config.ConfigService;
import ai.openagent.bootstrap.persistence.AgentRunRecord;
import ai.openagent.bootstrap.persistence.AgentRunRepository;
import ai.openagent.bootstrap.persistence.ToolExecutionRecord;
import ai.openagent.bootstrap.persistence.ToolExecutionRepository;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.identity.RequestContext;
import ai.openagent.framework.identity.RequestIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 运行轨迹服务（EVALUATION_PLAN.md Phase 1.2）：从 agent_runs +
 * tool_executions 组装单次运行的完整 Trace
 *
 * <p>
 * 两个消费者：TraceController 的 HTTP 端点（人工排查）与 Phase 2
 * EvalRunner 的进程内调用（失败自动导 trace）。归属校验对齐
 * AgentService.requireAccess 防线：越权按不存在处理（404），
 * API Key 限定 agent 范围时校验 run 的 agentId
 * </p>
 */
@Service
@RequiredArgsConstructor
public class TraceService {

    private final AgentRunRepository runRepository;
    private final ToolExecutionRepository executionRepository;
    private final ObjectMapper objectMapper;

    /**
     * 导出运行轨迹（含归属校验与工具入参打码）
     */
    public TraceVO export(String runId) {
        AgentRunRecord run = requireAccess(runId);
        List<TraceVO.ToolEventVO> events = executionRepository.listByRun(runId).stream()
                .map(this::toEvent)
                .toList();
        Long durationMs = run.completedAt() == null ? null : run.completedAt() - run.startedAt();
        return new TraceVO(
                run.id(),
                run.agentId(),
                run.sessionId(),
                run.status(),
                run.toolIterations(),
                run.errorCode(),
                run.errorMessage(),
                run.inputTokens(),
                run.outputTokens(),
                run.cacheReadTokens(),
                run.cacheWriteTokens(),
                run.startedAt(),
                run.completedAt(),
                durationMs,
                events);
    }

    /**
     * 按 ID 查询运行（含归属校验）：不存在或越权抛资源不存在异常，
     * 不暴露 run 存在性（对齐 V9 M2 统一防线）
     */
    private AgentRunRecord requireAccess(String runId) {
        AgentRunRecord run = runRepository
                .findById(runId)
                .orElseThrow(() -> new ClientException("run not found", BaseErrorCode.RESOURCE_NOT_FOUND));
        RequestIdentity identity = RequestContext.current()
                .orElseThrow(() -> new ClientException("unauthorized", BaseErrorCode.UNAUTHORIZED));
        if (!identity.isPlatformAdmin() && !run.userId().equals(identity.userId())) {
            throw new ClientException("run not found", BaseErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!identity.allowedAgentIds().isEmpty() && !identity.allowedAgentIds().contains(run.agentId())) {
            throw new ClientException("api key is not scoped to this agent", BaseErrorCode.FORBIDDEN);
        }
        return run;
    }

    private TraceVO.ToolEventVO toEvent(ToolExecutionRecord record) {
        return new TraceVO.ToolEventVO(
                record.sequence(),
                record.toolCallId(),
                record.toolName(),
                maskArguments(record.argumentsJson()),
                record.status(),
                record.resultContent(),
                record.errorCode(),
                record.errorMessage(),
                record.durationMs(),
                record.createdAt(),
                record.completedAt());
    }

    /**
     * 工具入参打码：顶层字段中键名疑似密钥（looksLikeSecret）的字符串值
     * 打码（复用 configs 打码规则）；非 JSON 对象原样返回
     */
    private String maskArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return argumentsJson;
        }
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            if (!(node instanceof ObjectNode object)) {
                return argumentsJson;
            }
            object.fieldNames().forEachRemaining(name -> {
                JsonNode value = object.get(name);
                if (ConfigService.looksLikeSecret(name) && value.isTextual()) {
                    object.put(name, ConfigService.maskSecret(value.asText()));
                }
            });
            return objectMapper.writeValueAsString(object);
        } catch (Exception error) {
            // 解析失败不阻断导出，原样返回
            return argumentsJson;
        }
    }
}
