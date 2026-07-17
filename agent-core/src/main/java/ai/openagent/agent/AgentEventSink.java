package ai.openagent.agent;

/**
 * Agent 事件接收端口：bootstrap 实现为「持久化 + ChatEventHub 广播」
 */
@FunctionalInterface
public interface AgentEventSink {

    void emit(AgentEvent event);
}
