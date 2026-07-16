package ai.openagent.agent;

@FunctionalInterface
public interface AgentEventSink {

    void emit(AgentEvent event);
}

