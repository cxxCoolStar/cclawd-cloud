package ai.openagent.agent.hook;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Hook 注册表（按注册顺序同步串行执行）
 *
 * <p>
 * 错误隔离语义：每个 hook 独立 try/catch，异常只记 warn
 * 日志、不中断运行、不影响后续 hook（fail-open）。护栏类 hook 约定用
 * {@link HookContext#reject(String)} 表达拒绝，不靠抛异常
 * </p>
 */
@Slf4j
public final class HookRegistry {

    private final Map<HookPoint, List<AgentHook>> hooks = new EnumMap<>(HookPoint.class);

    public HookRegistry() {}

    public HookRegistry(Collection<? extends AgentHook> initialHooks) {
        initialHooks.forEach(this::register);
    }

    /**
     * 空注册表（无 hook 的装配与测试使用）
     */
    public static HookRegistry empty() {
        return new HookRegistry();
    }

    public void register(AgentHook hook) {
        hooks.computeIfAbsent(hook.point(), point -> new ArrayList<>()).add(hook);
    }

    /**
     * 触发挂载点：写入 context.point 后按注册顺序执行该点全部 hook，
     * 单个 hook 异常 fail-open
     */
    public void fire(HookPoint point, HookContext context) {
        context.setPoint(point);
        for (AgentHook hook : hooks.getOrDefault(point, List.of())) {
            try {
                hook.onHook(context);
            } catch (RuntimeException error) {
                log.warn("[hook] 执行失败已忽略（fail-open），point={}, hook={}",
                        point, hook.getClass().getName(), error);
            }
        }
    }
}
