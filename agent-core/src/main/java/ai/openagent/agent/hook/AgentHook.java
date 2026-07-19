package ai.openagent.agent.hook;

/**
 * Agent 运行 hook（对齐 fastclaw hooks.go 的 HookFunc + 注册点）
 *
 * <p>
 * Spring 自动收集 {@code List<AgentHook>} 注入装配层。实现必须无状态、
 * 线程安全；运行期状态放 {@link HookContext#attributes()}。异常由
 * HookRegistry 隔离（fail-open，只记 warn 不中断运行），护栏类 hook
 * 约定用 {@link HookContext#reject(String)} 表达拒绝而不是抛异常
 * </p>
 */
public interface AgentHook {

    /**
     * 注册的挂载点
     */
    HookPoint point();

    /**
     * 触发回调（按注册顺序同步串行执行，不应长时间阻塞）
     */
    void onHook(HookContext context);
}
