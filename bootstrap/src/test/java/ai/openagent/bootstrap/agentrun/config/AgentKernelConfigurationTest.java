package ai.openagent.bootstrap.agentrun.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.openagent.agent.AgentConversationFactory;
import ai.openagent.agent.AgentKernel;
import ai.openagent.agent.ReActAgentKernel;
import ai.openagent.agent.hook.AgentHook;
import ai.openagent.agent.tool.AgentTool;
import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.agent.tool.ToolInvoker;
import ai.openagent.agent.tool.ToolRegistry;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.bootstrap.agentrun.hook.LoggingHook;
import ai.openagent.infra.ai.LLMService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Agent 内核装配冒烟：四个端口 + Spring 收集的 AgentHook 列表组装
 * HookRegistry，LoggingHook 的 7 个 hook bean 全部注册
 */
class AgentKernelConfigurationTest {

    @Configuration
    @Import({AgentKernelConfiguration.class, LoggingHook.class})
    static class TestConfig {

        @Bean
        LLMService llmService() {
            return (request, listener) -> {
                throw new UnsupportedOperationException("not used in wiring test");
            };
        }

        @Bean
        ToolRegistry toolRegistry() {
            return new ToolRegistry() {
                @Override
                public List<ToolDescriptor> availableTools(String agentId) {
                    return List.of();
                }

                @Override
                public AgentTool requireEnabled(String agentId, String toolName) {
                    throw new UnsupportedOperationException("not used in wiring test");
                }
            };
        }

        @Bean
        ToolInvoker toolInvoker() {
            return (call, context) -> ToolResult.success("unused");
        }

        @Bean
        AgentConversationFactory conversationFactory() {
            return command -> {
                throw new UnsupportedOperationException("not used in wiring test");
            };
        }
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    @Test
    void assemblesKernelWithLoggingHooks() {
        runner.run(context -> {
            assertInstanceOf(ReActAgentKernel.class, context.getBean(AgentKernel.class));
            // LoggingHook：BEFORE_*/AFTER_* 六个计时 + POST_TURN 摘要
            assertEquals(7, context.getBeanNamesForType(AgentHook.class).length);
        });
    }
}
