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
 * @param maxToolIterations 单次运行最大工具迭代轮数。默认 8 为有意偏离
 *                          FastClaw 的默认 20（V2 方案 20.2 问题 4）：
 *                          本地单机 + 首批只读工具，8 轮足够且减少失控消耗；
 *                          上限 20 与 FastClaw 对齐
 * @param runTimeout        单次 Agent 运行总超时
 */
@Validated
@ConfigurationProperties(prefix = "openagent.agent")
public record AgentProperties(
        @DefaultValue("8") @Min(1) @Max(20) int maxToolIterations,
        @DefaultValue("10m") Duration runTimeout) {}
