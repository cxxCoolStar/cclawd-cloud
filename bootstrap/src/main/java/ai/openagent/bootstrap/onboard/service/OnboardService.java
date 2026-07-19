package ai.openagent.bootstrap.onboard.service;

import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.config.ModelSettings;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.identity.service.AuthService;
import ai.openagent.bootstrap.onboard.controller.request.OnboardRequest;
import ai.openagent.bootstrap.persistence.DataSeeder;
import ai.openagent.bootstrap.persistence.ProviderRecord;
import ai.openagent.bootstrap.persistence.ProviderRepository;
import ai.openagent.bootstrap.persistence.UserRecord;
import ai.openagent.framework.identity.RequestContext;
import ai.openagent.framework.identity.RequestIdentity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Onboard 初始化服务（V8 M3，对照 fastclaw handlers_admin.go 的
 * onboard 语义裁剪为单机版）
 *
 * <p>
 * 语义 = 首次初始化：账密字段齐备且库内无密码用户（全新部署）时创建
 * super_admin 账号（V9 M2，首个业务 agent 归属该账号）；provider + apiKey
 * 非空时写入默认供应商连接配置（apiBase 缺省保留现值；model 同步到默认
 * agent）；agentName 非空且非 "default" 时创建首个业务 agent（"default"
 * 种子 agent 已存在，跳过避免重名）。sandbox 字段本版本忽略（环境变量配置）
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardService {

    private final ProviderRepository providerRepository;
    private final AgentService agentService;
    private final ModelSettings modelSettings;
    private final AuthService authService;

    /**
     * 执行首次初始化（幂等：重复提交按相同规则覆盖/跳过）
     */
    @Transactional
    public void onboard(OnboardRequest request) {
        long now = System.currentTimeMillis();
        Optional<UserRecord> account = authService.bootstrapAdmin(
                request.username(), request.email(), request.password(), request.displayName());
        account.ifPresent(user -> log.info("[onboard] 管理员账号已创建，username={}", user.username()));
        // /api/onboard 为公开端点（无 RequestContext）：新建账号时以其身份
        // 执行后续 agent 操作（agent 归属新账号），否则回落种子本地用户
        // （单机存量语义）
        RequestIdentity identity = account
                .<RequestIdentity>map(user -> new RequestIdentity(user.id(), null, null, user.role(), "onboard"))
                .orElseGet(() -> new RequestIdentity(
                        IdentityConstant.LOCAL_USER_ID, null, null, "super_admin", "onboard"));
        RequestContext.set(identity);
        try {
            // 对齐前端注释的 guard：provider 与 apiKey 同时非空才写供应商配置
            if (request.provider() != null && !request.provider().isBlank()
                    && request.apiKey() != null && !request.apiKey().isBlank()) {
                ProviderRecord current = providerRepository.findById(DataSeeder.DEFAULT_PROVIDER_ID).orElse(null);
                String apiBase = request.apiBase() == null || request.apiBase().isBlank()
                        ? current != null ? current.apiBase() : modelSettings.apiBase()
                        : request.apiBase();
                String model = request.model() == null || request.model().isBlank()
                        ? modelSettings.name()
                        : request.model();
                // V2 仅 OpenAI 兼容一种协议实现（apiType/authType 忽略）
                providerRepository.updateSettings(
                        DataSeeder.DEFAULT_PROVIDER_ID,
                        "openai",
                        apiBase,
                        request.apiKey(),
                        model,
                        modelSettings.temperature(),
                        modelSettings.maxTokens(),
                        now);
                if (request.model() != null && !request.model().isBlank()) {
                    agentService.updateAgentProfile(DataSeeder.DEFAULT_AGENT_ID, null, null, request.model());
                }
                log.info("[onboard] 供应商配置已写入，provider={}, model={}", request.provider(), model);
            }
            String agentName = request.agentName();
            if (agentName != null && !agentName.isBlank() && !"default".equalsIgnoreCase(agentName.trim())) {
                agentService.createAgent(agentName.trim(), null, request.model(), null);
                log.info("[onboard] 首个业务 agent 已创建，name={}, owner={}", agentName.trim(), identity.userId());
            }
        } finally {
            RequestContext.clear();
        }
    }
}
