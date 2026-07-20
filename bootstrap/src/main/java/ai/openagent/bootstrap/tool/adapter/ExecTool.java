package ai.openagent.bootstrap.tool.adapter;

import ai.openagent.agent.tool.ToolDescriptor;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.bootstrap.sandbox.DockerSandboxService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * exec 工具（V4 方案 4.3）
 *
 * <p>
 * 在 Docker 沙箱容器内执行 shell 命令。安全红线（V4 方案 4.5）：
 * 只经 {@link DockerSandboxService} 进入容器，代码库不存在宿主机 shell
 * 执行路径；双重门控——agent_tools 启停之外，全局
 * {@code openagent.sandbox.docker-enabled} 也必须打开。
 * 非零退出码不是工具失败：输出附带 Exit code 行作为 observation 回传
 * 模型（fastclaw Exec 附 Error 行的等价语义），由模型决定修正或换路
 * </p>
 */
@Component
public class ExecTool extends AbstractFileTool {

    private static final Pattern RECURSIVE_RM = Pattern.compile(
            "(?i)(?:^|[;&|\\n])\\s*(?:sudo\\s+)?rm\\s+(?=[^;&|\\n]*(?:-[a-z]*r[a-z]*|--recursive)(?:\\s|$))");
    private static final Pattern FIND_DELETE = Pattern.compile(
            "(?i)(?:^|[;&|\\n])\\s*find\\b[^;&|\\n]*\\s-delete(?:\\s|$)");
    private static final Pattern FILESYSTEM_DESTRUCTION = Pattern.compile(
            "(?i)(?:^|[;&|\\n])\\s*(?:sudo\\s+)?(?:mkfs(?:\\.[a-z0-9]+)?|wipefs)\\b");
    private static final Pattern BLOCK_DEVICE_WRITE = Pattern.compile(
            "(?i)(?:^|[;&|\\n])\\s*(?:sudo\\s+)?dd\\b[^;&|\\n]*\\bof\\s*=\\s*/dev/");

    private final DockerSandboxService sandboxService;

    public ExecTool(ObjectMapper objectMapper, DockerSandboxService sandboxService) {
        super(objectMapper);
        this.sandboxService = sandboxService;
    }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(
                "exec",
                "Run a shell command inside the agent's sandboxed Docker container. "
                        + "The session workspace is mounted at the working directory, so files "
                        + "created by write_file are directly accessible. Use this to run scripts, "
                        + "install packages, or inspect output. Returns combined stdout/stderr; "
                        + "a non-zero exit code is reported in the output, not as an error.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of(
                                        "type", "string",
                                        "description", "Shell command to run (executed via sh -c)"),
                                "workdir", Map.of(
                                        "type", "string",
                                        "description", "Subdirectory of the session workspace to run in (default: workspace root)"),
                                "timeout", Map.of(
                                        "type", "integer",
                                        "description", "Seconds before the command is killed (default: server tool timeout)")),
                        "required", List.of("command")),
                ToolDescriptor.Source.BUILTIN);
    }

    @Override
    protected ToolResult run(JsonNode args, ToolExecutionContext context) {
        if (!sandboxService.dockerEnabled()) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_NOT_ENABLED,
                    "exec is disabled by server configuration (OPENAGENT_SANDBOX_DOCKER_ENABLED)");
        }
        String command = requiredText(args, "command");
        if (command == null) {
            return missingArgument("command");
        }
        if (isDestructiveCommand(command)) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_ARGUMENT_INVALID,
                    "destructive command blocked; use a scoped file operation after explicit user confirmation");
        }
        String workdir = args.path("workdir").asText("");
        String normalizedWorkdir = normalizeWorkdir(workdir);
        if (normalizedWorkdir == null) {
            return ToolResult.failure(
                    ToolErrorCode.WORKSPACE_PATH_FORBIDDEN,
                    "workdir must stay inside the session workspace: " + workdir);
        }

        Duration remaining = Duration.between(Instant.now(), context.deadline());
        if (remaining.isNegative() || remaining.isZero()) {
            return ToolResult.failure(ToolErrorCode.TOOL_TIMEOUT, "tool execution deadline reached");
        }
        int requestedSeconds = args.path("timeout").asInt(0);
        Duration timeout = requestedSeconds > 0 ? Duration.ofSeconds(requestedSeconds) : remaining;
        timeout = timeout.compareTo(remaining) < 0 ? timeout : remaining;

        try {
            DockerSandboxService.ExecOutcome outcome = sandboxService.exec(
                    context.agentId(), context.sessionId(), command, normalizedWorkdir, timeout);
            if (outcome.timedOut()) {
                return ToolResult.failure(
                        ToolErrorCode.TOOL_TIMEOUT,
                        "command timed out after " + timeout.getSeconds() + "s\n" + outcome.output());
            }
            String output = outcome.output();
            if (outcome.exitCode() != 0) {
                output = output + "\nCommand failed with exit code " + outcome.exitCode()
                        + ". Inspect the output and adjust the command before retrying.";
            }
            return ToolResult.success(output.isBlank() ? "(no output)" : output);
        } catch (DockerSandboxService.SandboxException error) {
            return ToolResult.failure(ToolErrorCode.TOOL_EXECUTION_FAILED, error.getMessage());
        }
    }

    /**
     * workdir 归一化：仅允许会话 workspace 内的相对路径；
     * 拒绝绝对路径与 .. 逃逸，合法时返回容器内相对子路径（"" 表示会话根）
     */
    static boolean isDestructiveCommand(String command) {
        return RECURSIVE_RM.matcher(command).find()
                || FIND_DELETE.matcher(command).find()
                || FILESYSTEM_DESTRUCTION.matcher(command).find()
                || BLOCK_DEVICE_WRITE.matcher(command).find();
    }

    static String normalizeWorkdir(String workdir) {
        if (workdir == null || workdir.isBlank() || ".".equals(workdir)) {
            return "";
        }
        String normalized = workdir.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":")) {
            return null;
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String part : normalized.split("/")) {
            if (part.isBlank() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                return null;
            }
            parts.add(part);
        }
        return String.join("/", parts);
    }
}
