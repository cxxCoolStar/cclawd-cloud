package ai.openagent.agent;

/**
 * Agent 内核端口（V2 方案 7.1）
 *
 * <p>
 * 同步表达一条后台执行线程中的完整 Agent 运行；异步调度由
 * AgentRunCoordinator（bootstrap）负责，不把线程模型泄漏进核心领域。
 * 多轮 model → tool → model 循环的实现属于 M3
 * </p>
 */
public interface AgentKernel {

    AgentRunResult run(AgentRunCommand command, AgentEventSink eventSink);
}
