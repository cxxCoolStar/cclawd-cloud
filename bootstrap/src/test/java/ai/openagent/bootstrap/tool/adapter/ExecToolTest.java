package ai.openagent.bootstrap.tool.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.agent.tool.ToolArguments;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.bootstrap.agentrun.config.AgentProperties;
import ai.openagent.bootstrap.config.ConfigService;
import ai.openagent.bootstrap.config.InMemoryAgentRepository;
import ai.openagent.bootstrap.config.InMemoryConfigRepository;
import ai.openagent.bootstrap.config.ModelSettings;
import ai.openagent.bootstrap.sandbox.DockerCli;
import ai.openagent.bootstrap.sandbox.DockerSandboxService;
import ai.openagent.bootstrap.sandbox.config.SandboxProperties;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * exec 工具单测（V4 方案 4.3/4.5：门控、参数校验、workdir 安全、
 * 退出码与超时语义）
 */
class ExecToolTest {

    private static ToolExecutionContext context() {
        return new ToolExecutionContext(
                "run-1", "local-user", "default", "s1",
                Path.of("target/exec-tool-ws"),
                Instant.now().plusSeconds(60));
    }

    private static ExecTool tool(boolean dockerEnabled, Function<List<String>, DockerCli.CliResult> handler) {
        DockerCli cli = new DockerCli() {
            @Override
            public DockerCli.CliResult run(List<String> args) {
                return handler.apply(args);
            }

            @Override
            public DockerCli.CliResult run(List<String> args, Duration timeout) {
                return handler.apply(args);
            }
        };
        DockerSandboxService service = new DockerSandboxService(
                new SandboxProperties(dockerEnabled, "img", "1", "512m", "bridge"),
                new ToolProperties(Duration.ofSeconds(30), 65536, "target/exec-tool-ws", 1048576, false, 1048576),
                cli,
                configService());
        return new ExecTool(new ObjectMapper(), service);
    }

    /**
     * 空配置库（无 DB 覆盖）：dockerEnabled() 回退属性值
     */
    private static ConfigService configService() {
        return new ConfigService(
                new InMemoryConfigRepository(),
                new InMemoryAgentRepository(),
                new ObjectMapper(),
                new ModelSettings("kimi", "https://api.example", "test-key", "test-model", 0.6, 4096, null),
                new AgentProperties(8, Duration.ofMinutes(10), 80000, 20, 2048),
                new SandboxProperties(false, "img", "1", "512m", "bridge"));
    }

    private static ToolResult exec(ExecTool tool, String argsJson) {
        return tool.execute(new ToolArguments(argsJson), context());
    }

    @Test
    void rejectsWhenDockerGloballyDisabled() {
        ToolResult result = exec(tool(false, args -> new DockerCli.CliResult(0, "")), "{\"command\":\"ls\"}");
        assertFalse(result.success());
        assertEquals(ToolErrorCode.TOOL_NOT_ENABLED, result.errorCode());
    }

    @Test
    void rejectsMissingCommand() {
        ToolResult result = exec(tool(true, args -> new DockerCli.CliResult(0, "")), "{}");
        assertFalse(result.success());
        assertEquals(ToolErrorCode.TOOL_ARGUMENT_INVALID, result.errorCode());
    }

    @Test
    void returnsOutputWithExitCodeSuffix() {
        ExecTool tool = tool(true, args -> args.get(0).equals("exec")
                ? new DockerCli.CliResult(2, "partial output")
                : new DockerCli.CliResult(0, args.get(0).equals("inspect") ? "true" : ""));
        ToolResult result = exec(tool, "{\"command\":\"false\"}");
        assertTrue(result.success(), "非零退出码仍作为 observation 回传模型");
        assertTrue(result.content().contains("partial output"));
        assertTrue(result.content().endsWith("Exit code: 2"));
    }

    @Test
    void mapsTimeoutToToolTimeout() {
        ExecTool tool = tool(true, args -> args.get(0).equals("exec")
                ? new DockerCli.CliResult(DockerCli.CliResult.TIMED_OUT, "half")
                : new DockerCli.CliResult(0, args.get(0).equals("inspect") ? "true" : ""));
        ToolResult result = exec(tool, "{\"command\":\"sleep 99\",\"timeout\":1}");
        assertFalse(result.success());
        assertEquals(ToolErrorCode.TOOL_TIMEOUT, result.errorCode());
    }

    @Test
    void workdirNormalizationRejectsEscape() {
        assertEquals("", ExecTool.normalizeWorkdir(null));
        assertEquals("", ExecTool.normalizeWorkdir("."));
        assertEquals("sub/dir", ExecTool.normalizeWorkdir("sub/dir"));
        assertEquals("sub", ExecTool.normalizeWorkdir("./sub/"));
        assertEquals(null, ExecTool.normalizeWorkdir(".."));
        assertEquals(null, ExecTool.normalizeWorkdir("../other"));
        assertEquals(null, ExecTool.normalizeWorkdir("/etc"));
        assertEquals(null, ExecTool.normalizeWorkdir("a/../../b"));
    }

    @Test
    void workdirEscapeRejectedAsForbidden() {
        ToolResult result = exec(tool(true, args -> new DockerCli.CliResult(0, "")),
                "{\"command\":\"ls\",\"workdir\":\"..\"}");
        assertFalse(result.success());
        assertEquals(ToolErrorCode.WORKSPACE_PATH_FORBIDDEN, result.errorCode());
    }
}
