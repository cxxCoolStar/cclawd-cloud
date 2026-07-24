package ai.openagent.bootstrap.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.agentrun.config.AgentProperties;
import ai.openagent.bootstrap.config.ConfigService;
import ai.openagent.bootstrap.config.InMemoryAgentService;
import ai.openagent.bootstrap.config.InMemoryConfigRepository;
import ai.openagent.bootstrap.config.ModelSettings;
import ai.openagent.bootstrap.persistence.ConfigRepository;
import ai.openagent.bootstrap.sandbox.DockerCli.CliResult;
import ai.openagent.bootstrap.sandbox.config.SandboxProperties;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * DockerSandboxService 单测（fake DockerCli，无 Docker 守护进程可跑，
 * V4 方案 5.1）
 */
class DockerSandboxServiceTest {

    /**
     * 可编程 fake：按第一个参数（create/start/inspect/exec/rm/ps）路由响应
     */
    private static final class FakeDockerCli implements DockerCli {
        final List<List<String>> calls = new ArrayList<>();
        Function<List<String>, CliResult> handler = args -> new CliResult(0, "");

        @Override
        public CliResult run(List<String> args) {
            calls.add(args);
            return handler.apply(args);
        }

        @Override
        public CliResult run(List<String> args, Duration timeout) {
            return run(args);
        }

        List<List<String>> callsTo(String operation) {
            return calls.stream().filter(args -> args.get(0).equals(operation)).toList();
        }
    }

    private static ConfigService configService(InMemoryConfigRepository repository) {
        return new ConfigService(
                repository,
                new InMemoryAgentService(),
                new ObjectMapper(),
                new ModelSettings("kimi", "https://api.example", "test-key", "test-model", 0.6, 4096, null),
                new AgentProperties(8, Duration.ofMinutes(10), 80000, 20, 2048),
                new SandboxProperties(false, "img", "1", "512m", "bridge"));
    }

    private static DockerSandboxService service(FakeDockerCli cli, String workspaceRoot) {
        return service(cli, workspaceRoot, new InMemoryConfigRepository());
    }

    private static DockerSandboxService service(
            FakeDockerCli cli, String workspaceRoot, InMemoryConfigRepository configRepository) {
        return new DockerSandboxService(
                new SandboxProperties(true, "python:3.12-slim", "1", "512m", "bridge"),
                new ToolProperties(Duration.ofSeconds(30), 65536, workspaceRoot, 1048576, false, 1048576),
                cli,
                configService(configRepository));
    }

    @Test
    void createsContainerLazilyWithLimitsAndMount() {
        FakeDockerCli cli = new FakeDockerCli();
        cli.handler = args -> {
            if (args.get(0).equals("inspect")) {
                return new CliResult(1, "No such object"); // 不存在
            }
            return new CliResult(0, args.get(0).equals("create") ? "cid123" : "");
        };
        DockerSandboxService service = service(cli, "target/sandbox-test-ws");

        var outcome = service.exec("default", "s1", "echo hi", "", Duration.ofSeconds(10));

        assertEquals(0, outcome.exitCode());
        // create 恰好一次，参数含标签/名称/挂载/限额/网络/常驻命令
        assertEquals(1, cli.callsTo("create").size());
        List<String> create = cli.callsTo("create").get(0);
        String joined = String.join(" ", create);
        assertTrue(joined.contains("--label openagent=sandbox"));
        assertTrue(joined.contains("--name openagent-sandbox-default"));
        assertTrue(joined.contains(":/workspace:rw"));
        assertTrue(joined.contains("--cpus 1"));
        assertTrue(joined.contains("--memory 512m"));
        assertTrue(joined.contains("--network bridge"));
        assertTrue(joined.endsWith("python:3.12-slim tail -f /dev/null"));
        // exec 指向会话 workspace 目录
        List<String> exec = cli.callsTo("exec").get(0);
        assertEquals(List.of("exec", "-w", "/workspace/sessions/s1",
                "openagent-sandbox-default", "sh", "-c", "echo hi"), exec);
    }

    @Test
    void reusesRunningContainerWithoutRecreate() {
        FakeDockerCli cli = new FakeDockerCli();
        cli.handler = args -> new CliResult(0, args.get(0).equals("inspect") ? "true" : "");
        DockerSandboxService service = service(cli, "target/sandbox-test-ws");

        service.exec("default", "s1", "echo a", "", Duration.ofSeconds(10));
        service.exec("default", "s2", "echo b", "", Duration.ofSeconds(10));

        assertEquals(0, cli.callsTo("create").size());
        assertEquals(2, cli.callsTo("exec").size());
    }

    @Test
    void restartsStoppedContainer() {
        FakeDockerCli cli = new FakeDockerCli();
        cli.handler = args -> new CliResult(0, args.get(0).equals("inspect") ? "false" : "");
        DockerSandboxService service = service(cli, "target/sandbox-test-ws");

        service.exec("default", "s1", "echo a", "", Duration.ofSeconds(10));

        assertEquals(0, cli.callsTo("create").size());
        assertEquals(1, cli.callsTo("start").size());
    }

    @Test
    void recreatesWhenStartFails() {
        FakeDockerCli cli = new FakeDockerCli();
        cli.handler = args -> switch (args.get(0)) {
            case "inspect" -> new CliResult(0, "false");
            case "start" -> cli.callsTo("start").size() == 1
                    ? new CliResult(1, "container corrupted")
                    : new CliResult(0, "");
            default -> new CliResult(0, "");
        };
        DockerSandboxService service = service(cli, "target/sandbox-test-ws");

        service.exec("default", "s1", "echo a", "", Duration.ofSeconds(10));

        assertEquals(1, cli.callsTo("rm").size());
        assertEquals(1, cli.callsTo("create").size());
    }

    @Test
    void retriesOnceWhenContainerRemovedExternally() {
        FakeDockerCli cli = new FakeDockerCli();
        cli.handler = args -> switch (args.get(0)) {
            case "exec" -> cli.callsTo("exec").size() == 1
                    ? new CliResult(1, "Error response from daemon: No such container: abc")
                    : new CliResult(0, "ok");
            // rm 之前报告运行中；rm 之后报告不存在 → 触发重建
            case "inspect" -> cli.callsTo("rm").isEmpty()
                    ? new CliResult(0, "true")
                    : new CliResult(1, "No such object");
            default -> new CliResult(0, "");
        };
        DockerSandboxService service = service(cli, "target/sandbox-test-ws");

        var outcome = service.exec("default", "s1", "echo a", "", Duration.ofSeconds(10));

        assertEquals("ok", outcome.output());
        assertEquals(2, cli.callsTo("exec").size());
        assertEquals(1, cli.callsTo("create").size());
    }

    @Test
    void createFailureThrowsSandboxException() {
        FakeDockerCli cli = new FakeDockerCli();
        cli.handler = args -> args.get(0).equals("create")
                ? new CliResult(1, "permission denied")
                : new CliResult(1, "No such object");
        DockerSandboxService service = service(cli, "target/sandbox-test-ws");

        assertThrows(DockerSandboxService.SandboxException.class,
                () -> service.exec("default", "s1", "echo a", "", Duration.ofSeconds(10)));
    }

    @Test
    void containerNameSanitizesInvalidChars() {
        assertEquals("openagent-sandbox-default",
                DockerSandboxService.containerName("default"));
        assertEquals("openagent-sandbox-agent-1-2",
                DockerSandboxService.containerName("agent/1 2"));
    }

    @Test
    void cleanupRemovesStaleLabeledContainers() {
        FakeDockerCli cli = new FakeDockerCli();
        cli.handler = args -> args.get(0).equals("ps") ? new CliResult(0, "cid1\ncid2") : new CliResult(0, "");
        DockerSandboxService service = service(cli, "target/sandbox-test-ws");

        service.cleanupStaleContainers();

        assertEquals(2, cli.callsTo("rm").size());
    }

    @Test
    void cleanupSkippedWhenDockerDisabled() {
        FakeDockerCli cli = new FakeDockerCli();
        DockerSandboxService service = new DockerSandboxService(
                new SandboxProperties(false, "img", "1", "512m", "bridge"),
                new ToolProperties(Duration.ofSeconds(30), 65536, "target/x", 1048576, false, 1048576),
                cli,
                configService(new InMemoryConfigRepository()));
        service.cleanupStaleContainers();
        assertTrue(cli.calls.isEmpty());
    }

    @Test
    void dockerEnabledPrefersDbOverride() {
        InMemoryConfigRepository repository = new InMemoryConfigRepository();
        DockerSandboxService service = service(new FakeDockerCli(), "target/sandbox-test-ws", repository);

        // 无 DB 覆盖时回退属性值（helper 中属性为 true）
        assertTrue(service.dockerEnabled());

        repository.upsert(ConfigRepository.SCOPE_SYSTEM, "", ConfigService.KEY_SANDBOX, "{\"enabled\":false}");
        assertTrue(!service.dockerEnabled());

        repository.upsert(ConfigRepository.SCOPE_SYSTEM, "", ConfigService.KEY_SANDBOX, "{\"enabled\":true}");
        assertTrue(service.dockerEnabled());
    }
}
