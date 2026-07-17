package ai.openagent.agent;

/**
 * 会话上下文工厂端口：按运行命令装载完整上下文
 */
@FunctionalInterface
public interface AgentConversationFactory {

    AgentConversation open(AgentRunCommand command);
}
