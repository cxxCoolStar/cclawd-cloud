package ai.openagent.bootstrap.memory;

import ai.openagent.agent.AgentConversationScope;

import ai.openagent.bootstrap.memory.config.MemoryProperties;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 记忆服务（V3 方案 M2）
 *
 * <p>
 * 管理 agent 级 workspace 下的 MEMORY.md / USER.md / HISTORY.md：
 * <ul>
 *   <li>文件布局：{workspaceRoot}/{agentId}/MEMORY.md 等，
 *       是 agent 级而非 session 级文件；</li>
 *   <li>写入前经 {@link MemoryThreatScanner} 扫描：命中只 WARN 告警仍写入
 *       （避免数据丢失）；</li>
 *   <li>本地单用户模式落文件系统；多租户模式下 per-chatter 走 DB 行，
 *       V3 以 userId 参数预留该边界（当前固定 local-user），后续多用户版本
 *       替换为 DB 实现。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class MemoryService {

    public static final String MEMORY_FILE = "MEMORY.md";
    public static final String USER_FILE = "USER.md";
    public static final String HISTORY_FILE = "HISTORY.md";

    private static final DateTimeFormatter HISTORY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ToolProperties toolProperties;
    private final MemoryProperties memoryProperties;

    public MemoryService(ToolProperties toolProperties, MemoryProperties memoryProperties) {
        this.toolProperties = toolProperties;
        this.memoryProperties = memoryProperties;
    }

    public boolean enabled() {
        return memoryProperties.enabled();
    }

    /**
     * 读取长期记忆（文件不存在返回空串）
     */
    public String loadMemory(String agentId) {
        return loadMemory(agentId, null);
    }

    public String loadMemory(String agentId, AgentConversationScope scope) {
        return readQuietly(memoryHome(agentId, scope).resolve(MEMORY_FILE));
    }

    /**
     * 覆写长期记忆（写入前扫描，命中告警不阻断）
     */
    public void saveMemory(String userId, String agentId, String content) {
        saveMemory(userId, agentId, null, content);
    }

    public void saveMemory(
            String userId, String agentId, AgentConversationScope scope, String content) {
        scanBeforeWrite(agentId, MEMORY_FILE, content);
        write(memoryHome(agentId, scope).resolve(MEMORY_FILE), content);
    }

    /**
     * 读取用户画像（per-chatter 文件）
     */
    public String loadUserFile(String agentId) {
        return loadUserFile(agentId, null);
    }

    public String loadUserFile(String agentId, AgentConversationScope scope) {
        return readQuietly(memoryHome(agentId, scope).resolve(USER_FILE));
    }

    /**
     * 覆写用户画像（写入前扫描）
     */
    public void saveUserFile(String userId, String agentId, String content) {
        saveUserFile(userId, agentId, null, content);
    }

    public void saveUserFile(
            String userId, String agentId, AgentConversationScope scope, String content) {
        scanBeforeWrite(agentId, USER_FILE, content);
        write(memoryHome(agentId, scope).resolve(USER_FILE), content);
    }

    public String loadHistory(String agentId) {
        return loadHistory(agentId, null);
    }

    public String loadHistory(String agentId, AgentConversationScope scope) {
        return readQuietly(memoryHome(agentId, scope).resolve(HISTORY_FILE));
    }

    /**
     * 追加一条历史日志，格式：- [yyyy-MM-dd HH:mm:ss] entry
     */
    public void appendHistory(String agentId, String entry) {
        appendHistory(agentId, null, entry);
    }

    public void appendHistory(String agentId, AgentConversationScope scope, String entry) {
        Path path = memoryHome(agentId, scope).resolve(HISTORY_FILE);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path,
                    "- [" + LocalDateTime.now().format(HISTORY_TS) + "] " + entry + "\n",
                    StandardCharsets.UTF_8,
                    Files.exists(path)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /**
     * 记忆检索：在 MEMORY.md / USER.md / HISTORY.md 中按子串（不区分大小写）
     * 匹配行，返回 "文件名: 行内容" 列表（memory_search 工具的实现内核）
     */
    public List<String> search(String agentId, String query, int maxHits) {
        return search(agentId, null, query, maxHits);
    }

    public List<String> search(
            String agentId, AgentConversationScope scope, String query, int maxHits) {
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        List<String> hits = new java.util.ArrayList<>();
        for (String file : List.of(MEMORY_FILE, USER_FILE, HISTORY_FILE)) {
            String content = readQuietly(memoryHome(agentId, scope).resolve(file));
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && trimmed.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                    hits.add(file + ": " + trimmed);
                    if (hits.size() >= maxHits) {
                        return hits;
                    }
                }
            }
        }
        return hits;
    }

    /**
     * agent 级 workspace 目录
     * <p>
     * 如果配置了 evalWorkspaceRoot（评估模式），则使用该路径以隔离测试数据，
     * 避免覆盖真实 agent 的 MEMORY.md
     */
    public Path agentHome(String agentId) {
        String root = memoryProperties.getEvalWorkspaceRoot()
                .orElse(toolProperties.workspaceRoot());
        return Path.of(root).resolve(agentId);
    }

    public Path memoryHome(String agentId, AgentConversationScope scope) {
        Path agentHome = agentHome(agentId);
        if (scope == null || scope.sharedIdentity()) {
            return agentHome;
        }
        return agentHome.resolve("chats").resolve(scope.channel()).resolve(scope.memoryScopeId());
    }

    public Path historyLogHome(String agentId, AgentConversationScope scope) {
        if (scope == null || scope.sharedIdentity()) {
            return agentHome(agentId).resolve("memory").resolve("logs");
        }
        return memoryHome(agentId, scope).resolve("logs");
    }

    private void scanBeforeWrite(String agentId, String file, String content) {
        List<MemoryThreatScanner.Threat> threats = MemoryThreatScanner.scan(content);
        for (MemoryThreatScanner.Threat threat : threats) {
            log.warn("[memory] 安全扫描命中，agentId={}, file={}, type={}, pattern={}, context={}",
                    agentId, file, threat.type(), threat.pattern(), threat.context());
        }
    }

    private void write(Path path, String content) {
        String value = content == null ? "" : content;
        if (value.length() > memoryProperties.maxFileChars()) {
            throw new ClientException(
                    "memory file exceeds max size " + memoryProperties.maxFileChars() + " chars",
                    BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, value, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "";
        }
    }
}
