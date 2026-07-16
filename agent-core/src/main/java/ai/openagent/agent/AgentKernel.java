package ai.openagent.agent;

public interface AgentKernel {

    AgentRunHandle run(AgentRunCommand command, AgentEventSink eventSink);
}

