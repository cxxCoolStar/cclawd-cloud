package ai.openagent.bootstrap.agent.controller;

import ai.openagent.bootstrap.agent.service.AgentFileService;
import ai.openagent.bootstrap.agent.service.AgentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Agent workspace 文件接口（最小闭环：列表 + 内容）
 *
 * <p>
 * 对齐前端 api.ts 的消费形状：GET /api/agents/{id}/files?sessionId=...
 * 返回 {"files": [{path, size, modTime}]}；GET /api/agents/{id}/files/{path...}
 * 返回原始文件内容（fileUrl 逐段 URL 编码，?download=1 时附件下载）。
 * 响应头对齐 fastclaw setFileResponseHeaders：按扩展名推导 Content-Type、
 * nosniff、HTML 加 CSP sandbox
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class AgentFileController {

    private final AgentFileService agentFileService;
    private final AgentService agentService;

    /**
     * 列出 workspace 文件（sessionId 作用域过滤）
     */
    @GetMapping("/api/agents/{agentId}/files")
    public Map<String, List<AgentFileService.WorkspaceFileEntry>> listFiles(
            @PathVariable String agentId, @RequestParam(required = false) String sessionId) {
        agentService.requireAccess(agentId);
        return Map.of("files", agentFileService.listFiles(agentId, sessionId));
    }

    /**
     * 上传文件到会话 workspace（前端回形针按钮，multipart 字段名 "file"
     * 可多值；fastclaw 同款 64MB 解析上限已在 application.yml 配置）
     *
     * @return {"files": [{path, size}]}，path 为 agent 相对路径
     */
    @PostMapping("/api/agents/{agentId}/files")
    public Map<String, List<AgentFileService.UploadedFileEntry>> uploadFiles(
            @PathVariable String agentId,
            @RequestParam(required = false) String sessionId,
            @RequestParam("file") List<MultipartFile> files) throws IOException {
        agentService.requireAccess(agentId);
        List<AgentFileService.UploadFile> uploads = new java.util.ArrayList<>();
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename();
            if (filename == null || filename.isBlank()) {
                continue;
            }
            uploads.add(new AgentFileService.UploadFile(filename, file.getBytes()));
        }
        return Map.of("files", agentFileService.saveUploads(agentId, sessionId, uploads));
    }

    /**
     * 读取单个文件原始内容（路径逐段 URL 解码；拒绝越界）
     */
    @GetMapping("/api/agents/{agentId}/files/**")
    public void getFile(
            @PathVariable String agentId,
            @RequestParam(required = false) String download,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        agentService.requireAccess(agentId);
        String uri = request.getRequestURI();
        String prefix = "/api/agents/" + agentId + "/files/";
        String encoded = uri.substring(uri.indexOf(prefix) + prefix.length());
        StringBuilder relative = new StringBuilder();
        for (String segment : encoded.split("/")) {
            if (!relative.isEmpty()) {
                relative.append('/');
            }
            relative.append(URLDecoder.decode(segment, StandardCharsets.UTF_8));
        }
        Path file = agentFileService.resolveFile(agentId, relative.toString());

        String extension = extensionOf(file);
        response.setContentType(contentTypeOf(file.getFileName().toString(), extension));
        response.setHeader("X-Content-Type-Options", "nosniff");
        if ("html".equals(extension) || "htm".equals(extension)) {
            // 与 fastclaw 一致：Agent 生成的 HTML 不能触达应用 cookie/storage
            response.setHeader("Content-Security-Policy", "sandbox allow-scripts");
        }
        if ("1".equals(download)) {
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"" + file.getFileName().toString().replace("\"", "") + "\"");
        }
        response.setContentLengthLong(Files.size(file));
        Files.copy(file, response.getOutputStream());
        response.getOutputStream().flush();
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 已知文本扩展名兜底 text/plain（系统 mime 表在部分平台查不到 .md/.py），
     * 文本类型统一显式 charset=UTF-8——浏览器 fetch().text() 总是按 UTF-8
     * 解码，但响应头不带 charset 时下载/新标签页打开会按平台编码显示乱码
     */
    private static String contentTypeOf(String fileName, String extension) {
        String guessed = URLConnection.guessContentTypeFromName(fileName);
        if (guessed == null && TEXT_EXTENSIONS.contains(extension)) {
            guessed = "text/plain";
        }
        if (guessed == null) {
            return "application/octet-stream";
        }
        if (guessed.startsWith("text/") && !guessed.contains("charset")) {
            return guessed + ";charset=UTF-8";
        }
        return guessed;
    }

    private static final java.util.Set<String> TEXT_EXTENSIONS = java.util.Set.of(
            "txt", "md", "markdown", "py", "java", "js", "ts", "tsx", "jsx", "json",
            "yml", "yaml", "xml", "csv", "log", "sh", "html", "htm", "css", "sql", "toml");
}
