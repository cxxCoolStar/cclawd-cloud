package ai.openagent.bootstrap.chat.config;

import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 聊天回合执行线程池配置
 *
 * <p>
 * 聊天回合（调用模型 + 发布事件 + 持久化）在独立线程池中异步执行，
 * 使 HTTP 请求线程仅负责 SSE 转发；客户端断开后回合仍能跑完并落库。
 * 线程池参数由 {@link ChatProperties} 外置配置
 * </p>
 */
@Configuration
@RequiredArgsConstructor
public class ChatExecutionConfiguration {

    private final ChatProperties chatProperties;

    @Bean(name = "chatTurnExecutor")
    public Executor chatTurnExecutor() {
        ChatProperties.Executor settings = chatProperties.executor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("chat-turn-");
        executor.setCorePoolSize(settings.corePoolSize());
        executor.setMaxPoolSize(settings.maxPoolSize());
        executor.setQueueCapacity(settings.queueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) settings.awaitTermination().toSeconds());
        executor.initialize();
        return executor;
    }
}
