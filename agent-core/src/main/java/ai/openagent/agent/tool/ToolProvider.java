package ai.openagent.agent.tool;

import java.util.List;

public interface ToolProvider {

    List<ToolDescriptor> listTools(ToolScope scope);
}

