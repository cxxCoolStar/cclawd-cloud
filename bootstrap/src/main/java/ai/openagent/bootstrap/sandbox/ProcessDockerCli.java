package ai.openagent.bootstrap.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 基于 {@link ProcessBuilder} 的 docker CLI 实现
 *
 * <p>
 * stdout/stderr 合并读取；超时先 destroy 再 destroyForcibly，
 * 避免 docker exec 僵死占用工具线程
 * </p>
 */
@Component
public class ProcessDockerCli implements DockerCli {

    /**
     * 管理操作（create/start/inspect/rm）的默认超时，防 docker CLI 卡死
     */
    private static final Duration MANAGE_TIMEOUT = Duration.ofSeconds(30);

    @Override
    public CliResult run(List<String> args) {
        return run(args, MANAGE_TIMEOUT);
    }

    @Override
    public CliResult run(List<String> args, Duration timeout) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add("docker");
        command.addAll(args);
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException error) {
            // docker CLI 不存在 / 无法启动：退出码沿用非零语义
            return new CliResult(127, "failed to launch docker CLI: " + error.getMessage());
        }
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new CliResult(CliResult.TIMED_OUT, "docker CLI interrupted");
        }
        if (!finished) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            return new CliResult(CliResult.TIMED_OUT, readOutput(process));
        }
        return new CliResult(process.exitValue(), readOutput(process));
    }

    private static String readOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
