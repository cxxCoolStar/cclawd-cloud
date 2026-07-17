package ai.openagent.bootstrap.sandbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 沙箱配置属性（V4 方案 4.4）
 *
 * <p>
 * 对应 {@code openagent.sandbox.*} 配置项，示例：
 * <pre>
 * openagent:
 *   sandbox:
 *     docker-enabled: false
 *     image: python:3.12-slim
 *     cpus: "1"
 *     memory: 512m
 *     network: bridge
 * </pre>
 * </p>
 *
 * @param dockerEnabled Docker 沙箱全局开关（exec 工具的双重门控之一，
 *                      默认关闭；关闭时 exec 不可调用）
 * @param image         沙箱容器镜像（默认 python:3.12-slim，代码执行实用性）
 * @param cpus          容器 CPU 限额（docker --cpus）
 * @param memory        容器内存限额（docker --memory）
 * @param network       容器网络模式（docker --network；默认 bridge 对齐
 *                      fastclaw——沙箱需要出网做 pip install，可配 none 收紧）
 */
@ConfigurationProperties(prefix = "openagent.sandbox")
public record SandboxProperties(
        @DefaultValue("false") boolean dockerEnabled,
        @DefaultValue("python:3.12-slim") String image,
        @DefaultValue("1") String cpus,
        @DefaultValue("512m") String memory,
        @DefaultValue("bridge") String network) {}
