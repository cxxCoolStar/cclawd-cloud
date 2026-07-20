package ai.openagent.bootstrap.agentrun.hook;

import ai.openagent.agent.hook.AgentHook;
import ai.openagent.agent.hook.HookContext;
import ai.openagent.agent.hook.HookPoint;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 耗时日志 hook：在 BEFORE_* 三个挂载点将 startTime 记录到 HookContext.attributes，
 * 在 AFTER_* 三个挂载点打印耗时日志；POST_TURN 额外打印整轮运行摘要
 *
 * <p>
 * 一个挂载点一个 bean（AgentHook 接口单点注册），Spring 收集为
 * List<AgentHook> 注入 AgentKernelConfiguration
 * </p>
 */
@Slf4j
@Configuration
public class LoggingHook {

    private static final String ATTR_START_TIME = "loggingHook.startTime";

    @Bean
    AgentHook beforeSystemPromptTiming() {
        return timing(HookPoint.BEFORE_SYSTEM_PROMPT);
    }

    @Bean
    AgentHook afterSystemPromptTiming() {
        return timing(HookPoint.AFTER_SYSTEM_PROMPT);
    }

    @Bean
    AgentHook beforeModelCallTiming() {
        return timing(HookPoint.BEFORE_MODEL_CALL);
    }

    @Bean
    AgentHook afterModelCallTiming() {
        return timing(HookPoint.AFTER_MODEL_CALL);
    }

    @Bean
    AgentHook beforeToolCallTiming() {
        return timing(HookPoint.BEFORE_TOOL_CALL);
    }

    @Bean
    AgentHook afterToolCallTiming() {
        return timing(HookPoint.AFTER_TOOL_CALL);
    }

    @Bean
    AgentHook postTurnSummary() {
        return new AgentHook() {
            @Override
            public HookPoint point() {
                return HookPoint.POST_TURN;
            }

            @Override
            public void onHook(HookContext context) {
                log.info("[hook] 运行结束，runId={}, status={}, iterations={}, toolCalls={}, elapsedMs={}",
                        context.runId(),
                        context.runStatus(),
                        context.iterations(),
                        context.toolCallCount(),
                        Duration.between(context.startTime(), Instant.now()).toMillis());
            }
        };
    }

    /**
     * 计时 hook 对：BEFORE_* 记 startTime 到 attributes，AFTER_* 从
     * attributes 读回并打耗时（同一挂载点对共享同一 HookContext）
     */
    private static AgentHook timing(HookPoint point) {
        boolean before = point.name().startsWith("BEFORE");
        return new AgentHook() {
            @Override
            public HookPoint point() {
                return point;
            }

            @Override
            public void onHook(HookContext context) {
                if (before) {
                    context.attributes().put(ATTR_START_TIME, Instant.now());
                    return;
                }
                Object start = context.attributes().get(ATTR_START_TIME);
                long elapsedMs = start instanceof Instant instant
                        ? Duration.between(instant, Instant.now()).toMillis()
                        : -1;
                log.info("[hook] {} 完成，runId={}, elapsedMs={}", point, context.runId(), elapsedMs);
            }
        };
    }
}
