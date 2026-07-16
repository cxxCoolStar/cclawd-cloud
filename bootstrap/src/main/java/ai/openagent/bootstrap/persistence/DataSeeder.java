package ai.openagent.bootstrap.persistence;

import ai.openagent.bootstrap.config.ModelSettings;
import ai.openagent.bootstrap.identity.IdentityConstant;
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
 * 默认智能体。供应商与智能体的模型配置每次启动按环境变量刷新，
 * 使 {@code OPENAGENT_MODEL_*} 的变更无需手动改库即可生效
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

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
