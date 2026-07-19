package ai.openagent.bootstrap.agentrun.hook;

import ai.openagent.agent.hook.AgentHook;
import ai.openagent.agent.hook.HookContext;
import ai.openagent.agent.hook.HookPoint;
import ai.openagent.bootstrap.persistence.AgentRunRepository;
import ai.openagent.infra.ai.model.ModelResponse;
import ai.openagent.infra.ai.model.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Token 用量落库 hook（EVALUATION_PLAN.md Phase 1.1）：AFTER_MODEL_CALL
 * 把本次模型调用的 usage 增量累加进 agent_runs 四列
 *
 * <p>
 * 一次 run 有多次模型调用（ReAct 循环），而 HookContext 只在同一挂载点对内
 * 共享——跨调用聚合不走 attributes，改为逐次 SQL 自增
 * （input_tokens = input_tokens + ?），hook 保持无状态。失败路径
 * （modelResponse 为 null）与供应商未上报（ZERO）跳过；落库异常由
 * HookRegistry fail-open 隔离，不影响主流程
 * </p>
 */
@Slf4j
@Configuration
public class TokenUsageHook {

    @Bean
    AgentHook tokenUsagePersistence(AgentRunRepository runRepository) {
        return new AgentHook() {
            @Override
            public HookPoint point() {
                return HookPoint.AFTER_MODEL_CALL;
            }

            @Override
            public void onHook(HookContext context) {
                ModelResponse response = context.modelResponse();
                if (response == null) {
                    return;
                }
                TokenUsage usage = response.usage();
                if (TokenUsage.ZERO.equals(usage)) {
                    return;
                }
                runRepository.addTokenUsage(context.runId(), usage);
                log.debug("[hook] token 用量已累加，runId={}, usage={}", context.runId(), usage);
            }
        };
    }
}
