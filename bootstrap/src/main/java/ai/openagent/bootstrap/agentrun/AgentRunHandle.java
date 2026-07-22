package ai.openagent.bootstrap.agentrun;

import ai.openagent.agent.AgentRunResult;
import java.util.concurrent.CompletableFuture;

/** Handle for a queued run and its exact terminal result. */
public record AgentRunHandle(String runId, CompletableFuture<AgentRunResult> completion) {}
