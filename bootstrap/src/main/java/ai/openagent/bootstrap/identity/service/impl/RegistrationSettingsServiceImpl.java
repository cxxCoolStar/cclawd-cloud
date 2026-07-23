package ai.openagent.bootstrap.identity.service.impl;

import ai.openagent.bootstrap.identity.controller.vo.RegistrationStatusVO;
import ai.openagent.bootstrap.identity.service.RegistrationSettingsService;
import ai.openagent.bootstrap.persistence.ConfigRepository;
import ai.openagent.bootstrap.status.config.PlatformProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 注册开关服务实现：configs 表键 {@value #KEY} 存 JSON 布尔；
 * 从未经管理接口设置过时回落环境配置（保持 V9 M1 的默认形态）
 */
@Service
@RequiredArgsConstructor
public class RegistrationSettingsServiceImpl implements RegistrationSettingsService {

    /**
     * configs 表键
     */
    static final String KEY = "admin.registrationOpen";

    private final ConfigRepository configRepository;
    private final PlatformProperties platformProperties;

    @Override
    public boolean isOpen() {
        return configRepository
                .get(ConfigRepository.SCOPE_SYSTEM, "", KEY)
                .map("true"::equals)
                .orElse(platformProperties.registrationOpen());
    }

    @Override
    public void setOpen(boolean open) {
        // 注册开关为平台级配置，恒写 system scope（调用入口已有 super_admin 闸门）
        configRepository.upsert(ConfigRepository.SCOPE_SYSTEM, "", KEY, Boolean.toString(open));
    }
    @Override
    public RegistrationStatusVO registrationStatus() {
        UserAdminServiceImpl.requireSuperAdmin();
        return new RegistrationStatusVO(isOpen());
    }

    @Override
    public RegistrationStatusVO updateRegistration(boolean open) {
        UserAdminServiceImpl.requireSuperAdmin();
        setOpen(open);
        return new RegistrationStatusVO(isOpen());
    }
}
