package ai.openagent.bootstrap.tool.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.agent.tool.AgentTool;
import ai.openagent.agent.tool.ToolArguments;
import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolExecutionContext;
import ai.openagent.agent.tool.ToolResult;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * 文件工具族行为与安全边界测试（V2 方案 16.1：workspace 路径规范化、
 * 路径穿越、符号链接逃逸、大文件、二进制拒绝）
 */
class FileToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolProperties PROPS = new ToolProperties(
            Duration.ofSeconds(30), 65536, "./workspace", 1024 * 1024, false, 1024 * 1024);

    @TempDir
    Path workspaceRoot;

    private Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        workspace = workspaceRoot.resolve("default").resolve("sessions").resolve("s1");
        Files.createDirectories(workspace);
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(
                "run-1", "local-user", "default", "s1", workspace, Instant.now().plusSeconds(30));
    }

    private ToolResult invoke(AgentTool tool, String argsJson) {
        return tool.execute(new ToolArguments(argsJson), context());
    }

    // ==================== read_file / list_dir ====================

    @Test
    void readsUtf8File() throws IOException {
        Files.writeString(workspace.resolve("notes.md"), "# 你好\nworld\n");
        ToolResult result = invoke(new ReadFileTool(MAPPER, PROPS), "{\"path\":\"notes.md\"}");
        assertTrue(result.success());
        assertEquals("# 你好\nworld\n", result.content());
    }

    @Test
    void readMissingFileReturnsFileNotFound() {
        ToolResult result = invoke(new ReadFileTool(MAPPER, PROPS), "{\"path\":\"absent.md\"}");
        assertFalse(result.success());
        assertEquals(ToolErrorCode.FILE_NOT_FOUND, result.errorCode());
    }

    @Test
    void readOversizedFileReturnsFileTooLarge() throws IOException {
        Files.write(workspace.resolve("big.bin"), new byte[2 * 1024 * 1024]);
        ToolResult result = invoke(new ReadFileTool(MAPPER, PROPS), "{\"path\":\"big.bin\"}");
        assertFalse(result.success());
        assertEquals(ToolErrorCode.FILE_TOO_LARGE, result.errorCode());
    }

    @Test
    void readBinaryFileReturnsRefusalObservation() throws IOException {
        Files.write(workspace.resolve("img.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2});
        ToolResult result = invoke(new ReadFileTool(MAPPER, PROPS), "{\"path\":\"img.png\"}");
        // 二进制文件拒绝策略：返回成功 observation 并提示文件类型（模型不应重试）
        assertTrue(result.success());
        assertTrue(result.content().contains("binary file"));
    }

    @Test
    void listDirReturnsStandardFormat() throws IOException {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("a.txt"), "hello");
        ToolResult result = invoke(new ListDirTool(MAPPER), "{\"path\":\".\"}");
        assertTrue(result.success());
        assertTrue(result.content().contains("d docs/\n"), result.content());
        assertTrue(result.content().contains("f a.txt (5 bytes)\n"), result.content());
    }

    @Test
    void listMissingDirReturnsEmpty() {
        ToolResult result = invoke(new ListDirTool(MAPPER), "{\"path\":\"nope\"}");
        assertTrue(result.success());
        assertEquals("", result.content());
    }

    // ==================== write_file / edit_file ====================

    @Test
    void writeFileReturnsStandardResultText() throws IOException {
        ToolResult result = invoke(new WriteFileTool(MAPPER),
                "{\"path\":\"out/hello.txt\",\"content\":\"hi 世界\"}");
        assertTrue(result.success());
        // 前端 chat-screen.tsx 依赖 "Written N bytes" 前缀；字节数按 UTF-8
        int bytes = "hi 世界".getBytes(StandardCharsets.UTF_8).length;
        assertEquals("Written " + bytes + " bytes to out/hello.txt", result.content());
        assertEquals("hi 世界", Files.readString(workspace.resolve("out/hello.txt")));
    }

    @Test
    void writeFileRejectsDirectoryPath() {
        ToolResult result = invoke(new WriteFileTool(MAPPER), "{\"path\":\"out/\",\"content\":\"x\"}");
        assertFalse(result.success());
        assertEquals(ToolErrorCode.TOOL_ARGUMENT_INVALID, result.errorCode());
    }

    @Test
    void writeFileRejectsOverwriteAndPreservesExistingContent() throws IOException {
        Path existing = workspace.resolve("existing.txt");
        Files.writeString(existing, "original");

        ToolResult result = invoke(new WriteFileTool(MAPPER),
                "{\"path\":\"existing.txt\",\"content\":\"replacement\"}");

        assertFalse(result.success());
        assertEquals(ToolErrorCode.TOOL_ARGUMENT_INVALID, result.errorCode());
        assertTrue(result.errorMessage().contains("confirmation required before overwrite"));
        assertEquals("original", Files.readString(existing));
    }

    @Test
    void editFileReplacesUniqueSubstring() throws IOException {
        Files.writeString(workspace.resolve("cfg.txt"), "port=8080\nhost=localhost\n");
        ToolResult result = invoke(new EditFileTool(MAPPER),
                "{\"path\":\"cfg.txt\",\"old_string\":\"port=8080\",\"new_string\":\"port=9090\"}");
        assertTrue(result.success());
        assertEquals("Edited cfg.txt (1 replacement(s))", result.content());
        assertEquals("port=9090\nhost=localhost\n", Files.readString(workspace.resolve("cfg.txt")));
    }

    @Test
    void editFileRejectsAmbiguousMatchWithoutReplaceAll() throws IOException {
        Files.writeString(workspace.resolve("dup.txt"), "aaa\naaa\n");
        ToolResult result = invoke(new EditFileTool(MAPPER),
                "{\"path\":\"dup.txt\",\"old_string\":\"aaa\",\"new_string\":\"bbb\"}");
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("matches 2 locations"));

        ToolResult all = invoke(new EditFileTool(MAPPER),
                "{\"path\":\"dup.txt\",\"old_string\":\"aaa\",\"new_string\":\"bbb\",\"replace_all\":true}");
        assertTrue(all.success());
        assertEquals("Edited dup.txt (2 replacement(s))", all.content());
    }

    @Test
    void editFileNotFoundOldStringGuidesReread() throws IOException {
        Files.writeString(workspace.resolve("f.txt"), "hello\n");
        ToolResult result = invoke(new EditFileTool(MAPPER),
                "{\"path\":\"f.txt\",\"old_string\":\"nonexistent\",\"new_string\":\"x\"}");
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("re-read the file"));
    }

    // ==================== apply_patch ====================

    @Test
    void applyPatchAddUpdateDelete() throws IOException {
        Files.writeString(workspace.resolve("old.txt"), "line1\nline2\nline3\n");
        Files.writeString(workspace.resolve("legacy.txt"), "bye\n");
        String patch = """
                *** Begin Patch
                *** Add File: fresh.txt
                +created
                *** Update File: old.txt
                @@
                 line1
                -line2
                +line2 changed
                 line3
                *** Delete File: legacy.txt
                *** End Patch
                """;
        ToolResult result = invoke(new ApplyPatchTool(MAPPER),
                MAPPER.createObjectNode().put("input", patch).toString());
        assertTrue(result.success(), result.errorMessage());
        assertEquals("A fresh.txt\nU old.txt (1 hunk(s))\nD legacy.txt\n", result.content());
        assertEquals("created\n", Files.readString(workspace.resolve("fresh.txt")));
        assertEquals("line1\nline2 changed\nline3\n", Files.readString(workspace.resolve("old.txt")));
        assertFalse(Files.exists(workspace.resolve("legacy.txt")));
    }

    @Test
    void applyPatchFailedAnchorLeavesNoPartialWrites() throws IOException {
        Files.writeString(workspace.resolve("keep.txt"), "original\n");
        String patch = """
                *** Begin Patch
                *** Add File: should-not-exist.txt
                +x
                *** Update File: keep.txt
                @@
                 no_such_anchor_line
                -original
                +changed
                *** End Patch
                """;
        ToolResult result = invoke(new ApplyPatchTool(MAPPER),
                MAPPER.createObjectNode().put("input", patch).toString());
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("did not match"));
        // 两阶段语义：锚定失败时任何文件都不落盘
        assertFalse(Files.exists(workspace.resolve("should-not-exist.txt")));
        assertEquals("original\n", Files.readString(workspace.resolve("keep.txt")));
    }

    // ==================== 路径安全（16.1 攻击输入） ====================

    @Test
    void pathTraversalIsRejectedByAllTools() {
        String[] attacks = {"../outside.txt", "a/../../outside.txt", "..\\outside.txt"};
        for (String attack : attacks) {
            ToolResult read = invoke(new ReadFileTool(MAPPER, PROPS), "{\"path\":\"" + attack.replace("\\", "\\\\") + "\"}");
            assertFalse(read.success(), "read_file 应拒绝: " + attack);
            assertEquals(ToolErrorCode.WORKSPACE_PATH_FORBIDDEN, read.errorCode(), attack);

            ToolResult write = invoke(new WriteFileTool(MAPPER),
                    "{\"path\":\"" + attack.replace("\\", "\\\\") + "\",\"content\":\"x\"}");
            assertFalse(write.success(), "write_file 应拒绝: " + attack);
            assertEquals(ToolErrorCode.WORKSPACE_PATH_FORBIDDEN, write.errorCode(), attack);
        }
    }

    @Test
    void absolutePathsAreRejected() {
        for (String attack : new String[] {"/etc/passwd", "C:/Windows/win.ini", "C:\\\\Windows\\\\win.ini"}) {
            ToolResult result = invoke(new ReadFileTool(MAPPER, PROPS), "{\"path\":\"" + attack + "\"}");
            assertFalse(result.success(), "应拒绝绝对路径: " + attack);
            assertEquals(ToolErrorCode.WORKSPACE_PATH_FORBIDDEN, result.errorCode(), attack);
        }
    }

    @Test
    void applyPatchPathsAreAlsoConfined() {
        String patch = """
                *** Begin Patch
                *** Add File: ../escape.txt
                +pwned
                *** End Patch
                """;
        ToolResult result = invoke(new ApplyPatchTool(MAPPER),
                MAPPER.createObjectNode().put("input", patch).toString());
        assertFalse(result.success());
        assertEquals(ToolErrorCode.WORKSPACE_PATH_FORBIDDEN, result.errorCode());
        assertFalse(Files.exists(workspaceRoot.resolve("escape.txt")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // Windows 无特权进程通常无法创建符号链接
    void symlinkEscapeIsRejected() throws IOException {
        Path outside = workspaceRoot.resolve("secret");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("token.txt"), "secret");
        Files.createSymbolicLink(workspace.resolve("link"), outside);

        ToolResult result = invoke(new ReadFileTool(MAPPER, PROPS), "{\"path\":\"link/token.txt\"}");
        assertFalse(result.success());
        assertEquals(ToolErrorCode.WORKSPACE_PATH_FORBIDDEN, result.errorCode());
    }
}
