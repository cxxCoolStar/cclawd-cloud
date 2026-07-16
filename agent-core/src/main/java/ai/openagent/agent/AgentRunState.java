package ai.openagent.agent;

public enum AgentRunState {
    RECEIVED,
    LOAD_CONTEXT,
    CALL_MODEL,
    TOOL_REQUESTED,
    AUTHORIZE_TOOL,
    EXECUTE_TOOL,
    PERSIST_OBSERVATION,
    COMPACT_CONTEXT,
    COMPLETE,
    CANCELLED,
    FAILED
}

