package ai.openagent.bootstrap.agentrun.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * AgentProperties 边界校验：maxToolIterations 允许 1-20，越界启动失败
 */
class AgentPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(AgentProperties.class)
    static class TestConfig {}

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    @Test
    void defaultsMatchPlan() {
        runner.run(context -> {
            AgentProperties properties = context.getBean(AgentProperties.class);
            assertEquals(8, properties.maxToolIterations());
            assertEquals(Duration.ofMinutes(10), properties.runTimeout());
        });
    }

    @Test
    void acceptsBoundaryValues() {
        runner.withPropertyValues("openagent.agent.max-tool-iterations=20").run(context ->
                assertEquals(20, context.getBean(AgentProperties.class).maxToolIterations()));
        runner.withPropertyValues("openagent.agent.max-tool-iterations=1").run(context ->
                assertEquals(1, context.getBean(AgentProperties.class).maxToolIterations()));
    }

    @Test
    void rejectsOutOfRangeValues() {
        runner.withPropertyValues("openagent.agent.max-tool-iterations=21").run(context -> {
            assertTrue(context.getStartupFailure() != null, "超出上限必须启动失败");
        });
        runner.withPropertyValues("openagent.agent.max-tool-iterations=0").run(context -> {
            assertTrue(context.getStartupFailure() != null, "低于下限必须启动失败");
        });
    }
}
