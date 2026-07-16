package ai.openagent.bootstrap.status.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 平台级配置属性
 *
 * <p>
 * 对应 {@code openagent.*} 顶层配置项（模型配置见
 * {@link ai.openagent.bootstrap.config.ModelSettings}），示例：
 * <pre>
 * openagent:
 *   version: 0.1.0-SNAPSHOT
 *   registration-open: false
 *   sandbox:
 *     docker-enabled: false
 * </pre>
 * </p>
 *
 * @param version          平台版本号
 * @param registrationOpen 是否开放注册
 * @param sandbox          沙箱能力配置
 */
@ConfigurationProperties(prefix = "openagent")
public record PlatformProperties(
        @DefaultValue("0.1.0-SNAPSHOT") String version,
        @DefaultValue("false") boolean registrationOpen,
        @DefaultValue Sandbox sandbox) {

    /**
     * 沙箱能力配置
     *
     * @param dockerEnabled 是否启用 Docker 沙箱
     */
    public record Sandbox(@DefaultValue("false") boolean dockerEnabled) {}
}
