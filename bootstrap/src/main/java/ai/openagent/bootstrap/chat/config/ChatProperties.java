package ai.openagent.bootstrap.chat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 聊天域配置属性
 *
 * <p>
 * 对应 {@code openagent.chat.*} 配置项，示例：
 * <pre>
 * openagent:
 *   chat:
 *     heartbeat-interval: 30s
 *     executor:
 *       core-pool-size: 4
 *       max-pool-size: 16
 *       queue-capacity: 100
 *       await-termination: 20s
 * </pre>
 * </p>
 *
 * @param heartbeatInterval SSE 空闲保活心跳间隔（对齐 fastclaw 的 30s，防止
 *                          nginx/Cloudflare/ELB 掐断空闲连接；同时兼任死连接
 *                          探测——ping 写失败即回收连接）
 * @param executor          聊天回合线程池参数
 */
@ConfigurationProperties(prefix = "openagent.chat")
public record ChatProperties(
        @DefaultValue("30s") Duration heartbeatInterval,
        @DefaultValue Executor executor) {

    /**
     * 聊天回合线程池参数
     *
     * @param corePoolSize     核心线程数
     * @param maxPoolSize      最大线程数
     * @param queueCapacity    等待队列容量
     * @param awaitTermination 优雅停机等待时长
     */
    public record Executor(
            @DefaultValue("4") int corePoolSize,
            @DefaultValue("16") int maxPoolSize,
            @DefaultValue("100") int queueCapacity,
            @DefaultValue("20s") Duration awaitTermination) {}
}
