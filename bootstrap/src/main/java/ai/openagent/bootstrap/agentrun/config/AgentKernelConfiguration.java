package ai.openagent.bootstrap.agentrun.config;

import ai.openagent.agent.AgentConversationFactory;
import ai.openagent.agent.AgentKernel;
import ai.openagent.agent.ReActAgentKernel;
import ai.openagent.agent.tool.ToolInvoker;
import ai.openagent.agent.tool.ToolRegistry;
import ai.openagent.infra.ai.LLMService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 内核装配（V2 方案 5.1：bootstrap 负责把端口与实现装配起来）
 *
 * <p>
 * ReActAgentKernel 属于 agent-core 领域层不依赖 Spring，由此处组装
 * 四个端口：模型（infra-ai）、工具注册表与调用器（bootstrap tool 域）、
 * 会话上下文工厂（bootstrap agentrun 域）
 * </p>
 */
@Configuration
public class AgentKernelConfiguration {

    @Bean
    public AgentKernel agentKernel(
            LLMService llmService,
            ToolRegistry toolRegistry,
            ToolInvoker toolInvoker,
            AgentConversationFactory conversationFactory) {
        return new ReActAgentKernel(llmService, toolRegistry, toolInvoker, conversationFactory);
    }
}
