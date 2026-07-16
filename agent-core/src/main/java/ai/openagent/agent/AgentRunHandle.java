package ai.openagent.agent;

public interface AgentRunHandle {

    String runId();

    AgentRunState state();

    void cancel();
}

