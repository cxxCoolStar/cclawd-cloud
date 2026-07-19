package ai.openagent.bootstrap.identity.service;

/**
 * 注册开关服务
 *
 * <p>
 * 运行期可变的注册门控（V9 M2，/api/admin/registration）：DB（configs 表）
 * 优先，未写入过回落 {@code openagent.registration-open} 配置项
 * </p>
 */
public interface RegistrationSettingsService {

    /**
     * 当前是否开放注册
     */
    boolean isOpen();

    /**
     * 设置是否开放注册（持久化，立即生效）
     */
    void setOpen(boolean open);
}
