package ai.openagent.bootstrap.tool.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 工具执行配置属性
 *
 * <p>
 * 对应 {@code openagent.tools.*} 配置项（V2 方案第 11 章），示例：
 * <pre>
 * openagent:
 *   tools:
 *     execution-timeout: 30s
 *     max-result-chars: 65536
 *     workspace-root: ./workspace
 *     read-file-max-bytes: 1048576
 *     web-fetch-enabled: false
 *     web-fetch-max-bytes: 1048576
 * </pre>
 * </p>
 *
 * @param executionTimeout 单次工具执行超时
 * @param maxResultChars   工具结果最大字符数（超出截断并标记 truncated）
 * @param workspaceRoot    Agent workspace 根目录（会话目录布局
 *                         {workspaceRoot}/{agentId}/sessions/{sessionId}）
 * @param readFileMaxBytes read_file 单文件读取上限
 * @param webFetchEnabled  web_fetch 是否启用（默认关闭，启用后仍有 SSRF 校验）
 * @param webFetchMaxBytes web_fetch 响应大小上限
 */
@ConfigurationProperties(prefix = "openagent.tools")
public record ToolProperties(
        @DefaultValue("30s") Duration executionTimeout,
        @DefaultValue("65536") int maxResultChars,
        @DefaultValue("./workspace") String workspaceRoot,
        @DefaultValue("1048576") long readFileMaxBytes,
        @DefaultValue("false") boolean webFetchEnabled,
        @DefaultValue("1048576") long webFetchMaxBytes) {}
