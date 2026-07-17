package ai.openagent.infra.ai.model;

import java.util.Objects;

/**
 * 模型供应商连接配置
 *
 * <p>
 * infra-ai 不访问会话数据库（V2 方案 5.1 依赖约束），供应商的连接信息
 * 由 bootstrap 从持久层读出后经本记录传入。type 仅用于日志与协议分流，
 * V2 只有 OpenAI 兼容一种实现
 * </p>
 */
public record ModelProviderConfig(String type, String apiBase, String apiKey) {

    public ModelProviderConfig {
        type = Objects.requireNonNullElse(type, "openai");
        apiBase = Objects.requireNonNull(apiBase, "apiBase");
        apiKey = Objects.requireNonNullElse(apiKey, "");
    }
}
