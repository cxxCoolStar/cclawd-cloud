package ai.openagent.bootstrap.persistence;

import ai.openagent.bootstrap.config.ModelSettings;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.tool.ToolCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 种子数据初始化器
 *
 * <p>
 * 应用启动时保证本地单用户模式的默认数据就位：本地用户、默认供应商、
 * 默认智能体、默认工具配置。供应商与智能体的模型配置每次启动按环境变量
 * 刷新，使 {@code OPENAGENT_MODEL_*} 的变更无需手动改库即可生效；
 * 工具配置只做缺失补种（不覆盖用户显式启停），并将遗留的活跃运行标记为
 * INTERRUPTED（V2 不做进程级断点续跑）
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    /**
     * 默认智能体 ID（前端默认聊天页依赖）
     */
    public static final String DEFAULT_AGENT_ID = "default";

    /**
     * 默认供应商 ID
     */
    public static final String DEFAULT_PROVIDER_ID = "default-provider";

    private final UserRepository userRepository;
    private final ProviderRepository providerRepository;
    private final AgentRepository agentRepository;
    private final AgentToolRepository agentToolRepository;
    private final AgentRunRepository agentRunRepository;
    private final ModelSettings modelSettings;

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    @Transactional
    void seed() {
        long now = System.currentTimeMillis();
        seedLocalUser(now);
        seedDefaultProvider(now);
        seedDefaultAgent(now);
        seedDefaultAgentTools();
        recoverStaleRuns();
        log.info("[seed] 种子数据就绪，model={}, modelReady={}", modelSettings.name(), modelSettings.ready());
    }

    private void seedLocalUser(long now) {
        if (userRepository.findById(IdentityConstant.LOCAL_USER_ID).isPresent()) {
            return;
        }
        userRepository.insert(new UserRecord(
                IdentityConstant.LOCAL_USER_ID,
                "local",
                "local@openagent.invalid",
                "super_admin",
                "Local User",
                "active",
                now));
    }

    private void seedDefaultProvider(long now) {
        if (providerRepository.exists(DEFAULT_PROVIDER_ID)) {
            providerRepository.updateSettings(
                    DEFAULT_PROVIDER_ID,
                    modelSettings.provider(),
                    modelSettings.apiBase(),
                    value(modelSettings.apiKey()),
                    modelSettings.name(),
                    modelSettings.temperature(),
                    modelSettings.maxTokens(),
                    now);
            return;
        }
        providerRepository.insert(
                DEFAULT_PROVIDER_ID,
                modelSettings.provider(),
                "Default Provider",
                modelSettings.apiBase(),
                value(modelSettings.apiKey()),
                modelSettings.name(),
                modelSettings.temperature(),
                modelSettings.maxTokens(),
                now);
    }

    private void seedDefaultAgent(long now) {
        if (agentRepository.exists(DEFAULT_AGENT_ID)) {
            agentRepository.updateSettings(
                    DEFAULT_AGENT_ID, DEFAULT_PROVIDER_ID, modelSettings.name(), modelSettings.systemPrompt(), now);
            return;
        }
        agentRepository.insert(
                DEFAULT_AGENT_ID,
                IdentityConstant.LOCAL_USER_ID,
                "OpenAgent",
                "Default local chatbot",
                DEFAULT_PROVIDER_ID,
                modelSettings.name(),
                modelSettings.systemPrompt(),
                now);
    }

    /**
     * 为默认 Agent 补种内置工具配置：只补缺失项，不覆盖用户显式启停
     */
    private void seedDefaultAgentTools() {
        for (ToolCatalog tool : ToolCatalog.BUILTIN_TOOLS) {
            if (!agentToolRepository.exists(DEFAULT_AGENT_ID, tool.name())) {
                agentToolRepository.upsert(DEFAULT_AGENT_ID, tool.name(), tool.enabledDefault(), "{}");
            }
        }
    }

    /**
     * 启动恢复：进程退出时遗留的活跃运行标记为 INTERRUPTED
     */
    private void recoverStaleRuns() {
        int interrupted = agentRunRepository.markStaleRunsInterrupted();
        if (interrupted > 0) {
            log.warn("[seed] 发现 {} 个上次进程遗留的活跃运行，已标记为 INTERRUPTED", interrupted);
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
