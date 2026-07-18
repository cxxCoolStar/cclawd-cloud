package ai.openagent.bootstrap.config.controller.vo;

import ai.openagent.bootstrap.config.ConfigService;
import java.util.Map;

/**
 * /api/config 响应视图（前端 ConfigResponse 形状，V7 方案 3.2）
 */
public record ConfigResponseVO(
        Map<String, ProviderVO> providers,
        AgentsVO agents,
        Map<String, Object> channels,
        StorageVO storage,
        SandboxVO sandbox,
        Map<String, Object> prefs,
        SkillsVO skills,
        HooksVO hooks,
        MetaVO meta) {

    /**
     * 内置模型供应商（由 ModelSettings 构造，apiKey 已打码）
     */
    public record ProviderVO(String apiKey, String apiBase) {}

    /**
     * Agent 配置视图
     */
    public record AgentsVO(AgentDefaultsVO defaults) {}

    /**
     * Agent 默认值（DB 值，缺省回显 ModelSettings/AgentProperties 派生值）
     */
    public record AgentDefaultsVO(String model, Integer maxTokens, Double temperature, Integer maxToolIterations) {}

    /**
     * 存储视图（当前仅 sqlite）
     */
    public record StorageVO(String type, String dsn) {}

    /**
     * 沙箱视图（enabled 取 DB 覆盖 ?? 属性值，image 等回显当前属性值）
     */
    public record SandboxVO(boolean enabled, String backend, String image, String dockerImage) {}

    /**
     * 技能配置视图（secret 已打码）
     */
    public record SkillsVO(
            Map<String, ConfigService.SkillEntry> entries,
            Map<String, Map<String, ConfigService.SkillEntry>> agentEntries) {}

    /**
     * Webhook 视图（本版本未实现，恒 disabled 占位）
     */
    public record HooksVO(boolean enabled) {}

    /**
     * 前端继承徽章渲染提示
     */
    public record MetaVO(String systemDefaultModel, String serverTimezone) {}
}
