package ai.openagent.bootstrap.agentrun.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Agent 运行配置属性
 *
 * <p>
 * 对应 {@code openagent.agent.*} 配置项（V2 方案第 11 章），示例：
 * <pre>
 * openagent:
 *   agent:
 *     max-tool-iterations: 8
 *     run-timeout: 10m
 * </pre>
 * </p>
 *
 * @param maxToolIterations 单次运行最大工具迭代轮数。默认 8 轮，
 *                          本地单机 + 首批只读工具场景下足够使用且减少失控消耗；
 *                          上限 20 轮
 * @param runTimeout        单次 Agent 运行总超时
 * @param contextTokenThreshold 上下文压缩触发阈值（token 估算值），默认 80000
 * @param contextPruneTurnAge   近期保留完整的消息条数，默认保留 20 条
 * @param contextSummaryMaxTokens 压缩总结调用的 maxTokens，默认 2048
 */
@Validated
@ConfigurationProperties(prefix = "openagent.agent")
public record AgentProperties(
        @DefaultValue("8") @Min(1) @Max(20) int maxToolIterations,
        @DefaultValue("10m") Duration runTimeout,
        @DefaultValue("80000") @Min(50) int contextTokenThreshold,
        @DefaultValue("20") @Min(1) int contextPruneTurnAge,
        @DefaultValue("2048") @Min(256) int contextSummaryMaxTokens) {}
