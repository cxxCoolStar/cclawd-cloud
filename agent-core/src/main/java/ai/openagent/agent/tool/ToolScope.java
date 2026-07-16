package ai.openagent.agent.tool;

import ai.openagent.framework.identity.RequestIdentity;
import java.nio.file.Path;

public record ToolScope(RequestIdentity identity, String agentId, Path workspace) {}

