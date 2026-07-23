package ai.openagent.bootstrap.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.bootstrap.OpenAgentApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Workspace 文件接口测试（前端 Workspace 面板最小闭环）
 *
 * <p>
 * 对齐前端 api.ts 消费形状：列表返回 {path, size, modTime}（agent 相对
 * 路径 + 秒级时间戳），内容端点按扩展名给 Content-Type、nosniff、
 * HTML CSP sandbox、download=1 附件；越界路径 400、不存在 404
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/agent-files-test.db",
            "openagent.model.api-key=test-key",
            "openagent.tools.workspace-root=target/agent-files-ws"
        })
@AutoConfigureMockMvc
class AgentFileEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void seedWorkspace() throws Exception {
        Path sessionDir = Path.of("target", "agent-files-ws", "default", "sessions", "s1", "sub");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolveSibling("fib.py"), "print(1)\n");
        Files.writeString(sessionDir.resolve("notes.md"), "# 笔记\n中文内容\n");
        Files.writeString(sessionDir.resolveSibling("page.html"), "<html><body>hi</body></html>");
        // 另一个会话的文件不应混入 s1 作用域
        Path other = Path.of("target", "agent-files-ws", "default", "sessions", "s2");
        Files.createDirectories(other);
        Files.writeString(other.resolve("other.py"), "print(2)\n");
    }

    @Test
    void listsSessionScopedFilesWithFrontendShape() throws Exception {
        mockMvc.perform(get("/api/agents/default/files").param("sessionId", "s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files.length()").value(3))
                .andExpect(jsonPath("$.data.files[0].path").value("sessions/s1/fib.py"))
                .andExpect(jsonPath("$.data.files[0].size").isNumber())
                .andExpect(jsonPath("$.data.files[0].modTime").isNumber())
                .andExpect(jsonPath("$.data.files[1].path").value("sessions/s1/page.html"))
                .andExpect(jsonPath("$.data.files[2].path").value("sessions/s1/sub/notes.md"));
    }

    @Test
    void emptySessionReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/agents/default/files").param("sessionId", "no-such"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files.length()").value(0));
    }

    @Test
    void servesTextFileContent() throws Exception {
        mockMvc.perform(get("/api/agents/default/files/sessions/s1/sub/notes.md"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string(containsString("中文内容")));
    }

    @Test
    void htmlGetsCspSandboxHeader() throws Exception {
        mockMvc.perform(get("/api/agents/default/files/sessions/s1/page.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "sandbox allow-scripts"));
    }

    @Test
    void downloadParamAddsAttachmentDisposition() throws Exception {
        mockMvc.perform(get("/api/agents/default/files/sessions/s1/fib.py").param("download", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")));
    }

    @Test
    void rejectsPathEscape() throws Exception {
        // Spring 路由层对编码后的 ../ 直接 404（无 handler 匹配），
        // 即使到达控制器也会被 resolveFile 拒绝——两者都构成有效防护
        mockMvc.perform(get("/api/agents/default/files/..%2F..%2Fopenagent.db"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void missingFileReturns404() throws Exception {
        mockMvc.perform(get("/api/agents/default/files/sessions/s1/nope.py"))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadsMultipleFilesIntoSessionScope() throws Exception {
        MockMultipartFile first = new MockMultipartFile(
                "file", "hello.txt", "text/plain", "你好".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile second = new MockMultipartFile(
                "file", "data.csv", "text/csv", "a,b".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/agents/default/files")
                        .file(first)
                        .file(second)
                        .param("sessionId", "upload-multi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files.length()").value(2))
                .andExpect(jsonPath("$.data.files[0].path").value("sessions/upload-multi/hello.txt"))
                .andExpect(jsonPath("$.data.files[0].size").isNumber())
                .andExpect(jsonPath("$.data.files[1].path").value("sessions/upload-multi/data.csv"));
        // 上传后可列出、可读
        mockMvc.perform(get("/api/agents/default/files").param("sessionId", "upload-multi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files.length()").value(2));
        mockMvc.perform(get("/api/agents/default/files/sessions/upload-multi/hello.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("你好")));
    }

    @Test
    void uploadStripsDirectoryComponentsFromFilename() throws Exception {
        MockMultipartFile evil = new MockMultipartFile(
                "file", "../../evil.txt", "text/plain", "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/agents/default/files")
                        .file(evil)
                        .param("sessionId", "upload-s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files[0].path").value("sessions/upload-s/evil.txt"));
    }
}
