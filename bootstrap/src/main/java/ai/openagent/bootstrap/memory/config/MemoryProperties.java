package ai.openagent.bootstrap.memory.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 记忆配置属性（V3 方案 4.3）
 *
 * <p>
 * 对应 {@code openagent.memory.*} 配置项，示例：
 * <pre>
 * openagent:
 *   memory:
 *     enabled: true
 *     auto-persist-enabled: true
 *     auto-persist-interval: 5
 *     max-file-chars: 32768
 * </pre>
 * </p>
 *
 * @param enabled             记忆总开关：关闭后不注入 MEMORY.md/USER.md，
 *                            memory_search 返回未启用
 * @param autoPersistEnabled  自动记忆提取开关（AutoPersistMemory 语义）
 * @param autoPersistInterval 每 N 条用户消息触发一次提取（fastclaw
 *                            AutoPersist.EveryNTurns 语义：按 (agent, user)
 *                            的用户消息总数取模判定）
 * @param maxFileChars        单个记忆文件大小上限（字符），超出拒绝写入
 */
@Validated
@ConfigurationProperties(prefix = "openagent.memory")
public record MemoryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean autoPersistEnabled,
        @DefaultValue("5") @Min(1) @Max(100) int autoPersistInterval,
        @DefaultValue("32768") @Min(1024) int maxFileChars) {}
