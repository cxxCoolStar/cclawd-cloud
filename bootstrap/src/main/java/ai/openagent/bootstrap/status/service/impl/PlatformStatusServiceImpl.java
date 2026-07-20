package ai.openagent.bootstrap.status.service.impl;

import ai.openagent.bootstrap.config.ModelSettings;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.identity.service.RegistrationSettingsService;
import ai.openagent.bootstrap.persistence.AgentRepository;
import ai.openagent.bootstrap.persistence.DataSeeder;
import ai.openagent.bootstrap.persistence.ProviderRepository;
import ai.openagent.bootstrap.status.PlatformCapabilities;
import ai.openagent.bootstrap.status.config.PlatformProperties;
import ai.openagent.bootstrap.status.controller.vo.PlatformStatusVO;
import ai.openagent.bootstrap.status.service.PlatformStatusService;
import ai.openagent.framework.identity.RequestContext;
import ai.openagent.framework.identity.RequestIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 平台状态服务实现
 */
@Service
public class PlatformStatusServiceImpl implements PlatformStatusService {

    private final Instant startedAt = Instant.now();
    private final int port;
    private final PlatformProperties platformProperties;
    private final AgentRepository agentRepository;
    private final ProviderRepository providerRepository;
    private final ModelSettings modelSettings;
    private final RegistrationSettingsService registrationSettingsService;

    public PlatformStatusServiceImpl(
            @Value("${server.port:18953}") int port,
            PlatformProperties platformProperties,
            AgentRepository agentRepository,
            ProviderRepository providerRepository,
            ModelSettings modelSettings,
            RegistrationSettingsService registrationSettingsService) {
        this.port = port;
        this.platformProperties = platformProperties;
        this.agentRepository = agentRepository;
        this.providerRepository = providerRepository;
        this.modelSettings = modelSettings;
        this.registrationSettingsService = registrationSettingsService;
    }

    @Override
    public PlatformStatusVO currentStatus() {
        // /api/status 为公开端点：已认证调用方看自己名下的 agent，
        // 匿名调用方维持原行为（种子 local-user 的 agent 列表）
        String userId = RequestContext.current()
                .map(RequestIdentity::userId)
                .filter(id -> !id.isBlank())
                .orElse(IdentityConstant.LOCAL_USER_ID);
        List<PlatformStatusVO.AgentStatusVO> agents =
                agentRepository.listByUser(userId).stream()
                        .map(PlatformStatusVO.AgentStatusVO::from)
                        .toList();
        // V8 M3：env 或 DB（onboard 写入）任一侧配好 apiKey 即视为已初始化
        boolean configured = modelSettings.ready() || providerRepository
                .findById(DataSeeder.DEFAULT_PROVIDER_ID)
                .map(provider -> provider.apiKey() != null && !provider.apiKey().isBlank())
                .orElse(false);
        return new PlatformStatusVO(
                true,
                registrationSettingsService.isOpen(),
                true,
                port,
                "local",
                platformProperties.version(),
                formatDuration(Duration.between(startedAt, Instant.now())),
                agents,
                List.of(),
                new PlatformStatusVO.ProviderStatusVO(
                        modelSettings.provider(),
                        modelSettings.name(),
                        modelSettings.apiBase(),
                        configured ? "configured" : ""),
                configured,
                0,
                0,
                PlatformCapabilities.v1Defaults(platformProperties.sandbox().dockerEnabled()));
    }

    /**
     * 将时长格式化为紧凑的人类可读字符串（如 "1d 2h 3m 4s"）
     */
    static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long remainder = seconds % 60;

        if (days > 0) {
            return "%dd %dh %dm %ds".formatted(days, hours, minutes, remainder);
        }
        if (hours > 0) {
            return "%dh %dm %ds".formatted(hours, minutes, remainder);
        }
        if (minutes > 0) {
            return "%dm %ds".formatted(minutes, remainder);
        }
        return "%ds".formatted(remainder);
    }
}
