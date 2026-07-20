package ai.openagent.agent.hook;

import ai.openagent.agent.AgentRunStatus;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.infra.ai.model.ModelRequest;
import ai.openagent.infra.ai.model.ModelResponse;
import ai.openagent.infra.ai.model.ToolCall;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Hook 上下文（单线程运行期内可变使用）
 *
 * <p>
 * 身份字段构造时固定；point 由 HookRegistry.fire 触发时写入；载荷字段
 * 按挂载点填充（见各字段说明）。attributes 供同一挂载点对的 Before→After
 * 传递运行期状态（如 LoggingHook 记 startTime）——hook 实现必须无状态、
 * 线程安全，运行期状态一律放这里
 * </p>
 */
@Getter
@Accessors(fluent = true)
public final class HookContext {

    private final String runId;
    private final String userId;
    private final String agentId;
    private final String sessionId;

    /**
     * 会话 workspace（BEFORE_SYSTEM_PROMPT 时 conversation 尚未打开，可为空）
     */
    @Setter
    private Path workspace;

    /**
     * 当前挂载点（HookRegistry.fire 触发时写入）
     */
    private HookPoint point;

    /**
     * 上下文创建时间（POST_TURN 上下文在运行开始时创建，即运行开始时间）
     */
    @Setter
    private Instant startTime = Instant.now();

    /**
     * 供 Before→After 传状态的可变 map（如计时 startTime）
     */
    private final Map<String, Object> attributes = new HashMap<>();

    /**
     * 模型请求：BEFORE_MODEL_CALL 可读写（kernel 触发后读回生效），其余点只读
     */
    @Setter
    private ModelRequest modelRequest;

    /**
     * 模型响应：AFTER_MODEL_CALL 成功时携带
     */
    @Setter
    private ModelResponse modelResponse;

    /**
     * 失败信息：AFTER_* / POST_TURN 失败路径携带
     */
    @Setter
    private Throwable error;

    /**
     * 本次工具调用：BEFORE_TOOL_CALL / AFTER_TOOL_CALL 携带
     */
    @Setter
    private ToolCall toolCall;

    /**
     * 工具结果：AFTER_TOOL_CALL 携带（含 hook 拒绝合成的失败结果）
     */
    @Setter
    private ToolResult toolResult;

    /**
     * POST_TURN：实际执行的工具迭代轮数
     */
    @Setter
    private int iterations;

    /**
     * POST_TURN：实际处理的工具调用总数
     */
    @Setter
    private int toolCallCount;

    /**
     * POST_TURN：运行终态
     */
    @Setter
    private AgentRunStatus runStatus;

    private String rejectionReason;

    public HookContext(String runId, String userId, String agentId, String sessionId) {
        this.runId = runId;
        this.userId = userId;
        this.agentId = agentId;
        this.sessionId = sessionId;
    }

    /**
     * 供 HookRegistry.fire 触发时写入当前挂载点
     */
    void setPoint(HookPoint point) {
        this.point = point;
    }

    /**
     * 拒绝本次工具调用：仅 BEFORE_TOOL_CALL 生效——kernel 跳过执行并合成
     * TOOL_CALL_REJECTED 失败结果作为 observation 回灌模型（V2 方案 6.1
     * 「策略拒绝也作为 observation 返回模型」）。其余挂载点不可中断运行，
     * 护栏类 hook 约定用显式 reject 表达拒绝，不靠抛异常
     */
    public void reject(String reason) {
        this.rejectionReason = reason;
    }
}
