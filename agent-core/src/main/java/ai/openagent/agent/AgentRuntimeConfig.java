package ai.openagent.agent;

import java.time.Duration;

/**
 * 一次 Agent 运行的有效配置（V2 方案 3.1 Agent 配置）
 *
 * @param maxToolIterations 最大工具迭代轮数（默认 8，允许 1-20，见 20.2 问题 4）
 * @param runTimeout        整次运行超时
 * @param toolTimeout       单次工具执行超时
 */
public record AgentRuntimeConfig(int maxToolIterations, Duration runTimeout, Duration toolTimeout) {}
