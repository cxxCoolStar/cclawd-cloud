package ai.openagent.bootstrap.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.tool.config.ToolProperties;
import ai.openagent.bootstrap.workspace.config.WorkspaceProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Workspace 版本历史单测
 */
class WorkspaceHistoryServiceTest {

    @BeforeAll
    static void assumeGit() {
        boolean gitAvailable = false;
        try {
            gitAvailable = new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception ignored) {
            // git 不可用则跳过
        }
        Assumptions.assumeTrue(gitAvailable, "需要 git 运行 workspace 历史测试");
    }

    private static WorkspaceHistoryService service(Path workspaceRoot) {
        return new WorkspaceHistoryService(
                new ToolProperties(Duration.ofSeconds(30), 65536, workspaceRoot.toString(), 1048576, false, 1048576),
                new WorkspaceProperties(true));
    }

    private static String gitLog(Path repo, Path workTree) throws Exception {
        Process process = new ProcessBuilder(
                        "git", "--git-dir=" + repo, "--work-tree=" + workTree,
                        "log", "--oneline")
                .redirectErrorStream(true)
                .start();
        process.waitFor();
        return new String(process.getInputStream().readAllBytes());
    }

    @Test
    void commitsSnapshotAfterRun(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("default").resolve("sessions").resolve("s1");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("fib.py"), "print(1)");
        WorkspaceHistoryService service = service(temp);

        service.commitAfterRun("default", "s1", "run-1");

        Path repo = service.historyRepo("default", "s1");
        // bare 仓库在 workspace 外（.history 目录），workspace 内无 .git
        assertTrue(Files.isDirectory(repo));
        assertFalse(Files.exists(workspace.resolve(".git")));
        String log = gitLog(repo, workspace);
        assertTrue(log.contains("turn run-1"), "应有一次提交: " + log);
    }

    @Test
    void skipsEmptyCommit(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("default").resolve("sessions").resolve("s1");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("a.txt"), "v1");
        WorkspaceHistoryService service = service(temp);

        service.commitAfterRun("default", "s1", "run-1");
        service.commitAfterRun("default", "s1", "run-2"); // 无变更

        String log = gitLog(service.historyRepo("default", "s1"), workspace);
        assertEquals(1, log.lines().count(), "无变更不产生空提交: " + log);
    }

    @Test
    void secondCommitCapturesModification(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("default").resolve("sessions").resolve("s1");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("a.txt"), "v1");
        WorkspaceHistoryService service = service(temp);

        service.commitAfterRun("default", "s1", "run-1");
        Files.writeString(workspace.resolve("a.txt"), "v2-corrupted");
        service.commitAfterRun("default", "s1", "run-2");

        String log = gitLog(service.historyRepo("default", "s1"), workspace);
        assertEquals(2, log.lines().count());
        // 可用 git 恢复到 run-1 的版本
        Process checkout = new ProcessBuilder(
                        "git", "--git-dir=" + service.historyRepo("default", "s1"),
                        "--work-tree=" + workspace,
                        "checkout", "HEAD~1", "--", "a.txt")
                .redirectErrorStream(true).start();
        assertEquals(0, checkout.waitFor());
        assertEquals("v1", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void disabledWhenHistoryOff(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("default").resolve("sessions").resolve("s1");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("a.txt"), "v1");
        WorkspaceHistoryService service = new WorkspaceHistoryService(
                new ToolProperties(Duration.ofSeconds(30), 65536, temp.toString(), 1048576, false, 1048576),
                new WorkspaceProperties(false));

        assertFalse(service.enabled());
        service.commitAfterRun("default", "s1", "run-1");
        assertFalse(Files.exists(temp.resolve(".history")));
    }
}
