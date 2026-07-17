package ai.openagent.bootstrap.tool.adapter;

import ai.openagent.agent.tool.ToolErrorCode;
import ai.openagent.agent.tool.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Workspace 路径安全解析（V2 方案 12.1，对齐 fastclaw localfs.resolvePath
 * 并按方案要求加强符号链接校验）
 *
 * <p>
 * 规则：
 * <ul>
 *   <li>拒绝绝对路径与盘符路径（模型只能使用 workspace 相对路径）；</li>
 *   <li>normalize 后必须仍位于 workspace 根之下（{@code ..} 穿越拒绝，
 *       fastclaw 以 {@code Clean("/"+path)} 剥前导 ..，此处显式拒绝——
 *       给模型明确的错误比静默重定向更可恢复）；</li>
 *   <li>对最近已存在的祖先目录做 {@code toRealPath()} 校验，不跟随
 *       逃出 workspace 的符号链接（方案 12.1 对 fastclaw 的有意加强，
 *       fastclaw 将 symlink 视为用户自担边界）。</li>
 * </ul>
 * 校验失败以 {@link PathViolation} 表达，调用方转为
 * WORKSPACE_PATH_FORBIDDEN 工具失败结果
 * </p>
 */
final class WorkspacePaths {

    /**
     * 路径校验失败（消息不泄露宿主机绝对路径，方案 12.1）
     */
    static final class PathViolation extends RuntimeException {
        PathViolation(String message) {
            super(message);
        }

        ToolResult toResult() {
            return ToolResult.failure(ToolErrorCode.WORKSPACE_PATH_FORBIDDEN, getMessage());
        }
    }

    private WorkspacePaths() {}

    /**
     * 解析 workspace 相对路径为安全的绝对路径
     *
     * @param workspace 会话 workspace 根（可能尚不存在——首次写入前）
     * @param rawPath   模型提供的相对路径
     */
    static Path resolve(Path workspace, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new PathViolation("path is required");
        }
        String normalizedInput = rawPath.trim();
        Path candidate = Path.of(normalizedInput);
        if (candidate.isAbsolute() || normalizedInput.startsWith("/") || normalizedInput.startsWith("\\")
                || hasDriveLetter(normalizedInput)) {
            throw new PathViolation("absolute paths are not allowed; use a path relative to your workspace");
        }
        Path workspaceRoot = workspace.toAbsolutePath().normalize();
        Path resolved = workspaceRoot.resolve(candidate).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new PathViolation("path escapes the workspace: " + rawPath);
        }
        assertNoSymlinkEscape(workspaceRoot, resolved);
        return resolved;
    }

    /**
     * 从目标路径最近已存在的祖先向上校验真实路径仍在 workspace 内
     * （方案 12.1：新文件写入时校验最近已存在父目录的真实路径）
     */
    private static void assertNoSymlinkEscape(Path workspaceRoot, Path resolved) {
        Path existing = resolved;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null || !existing.startsWith(workspaceRoot)) {
            // workspace 根尚不存在（首次使用）时 existing 会走到根之外的
            // 已存在祖先——此时只要 normalize 校验已通过即安全，真实路径
            // 校验从 workspace 根自身开始
            if (!Files.exists(workspaceRoot)) {
                return;
            }
            existing = workspaceRoot;
        }
        try {
            Path real = existing.toRealPath();
            Path realRoot = Files.exists(workspaceRoot) ? workspaceRoot.toRealPath() : workspaceRoot;
            if (!real.startsWith(realRoot)) {
                throw new PathViolation("path resolves outside the workspace (symlink escape)");
            }
        } catch (IOException error) {
            throw new PathViolation("path could not be validated: " + error.getClass().getSimpleName());
        }
    }

    private static boolean hasDriveLetter(String path) {
        return path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
    }
}
