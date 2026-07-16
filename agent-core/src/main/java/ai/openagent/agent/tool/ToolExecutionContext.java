package ai.openagent.agent.tool;

import java.time.Duration;

public record ToolExecutionContext(String runId, ToolScope scope, Duration timeout) {}

