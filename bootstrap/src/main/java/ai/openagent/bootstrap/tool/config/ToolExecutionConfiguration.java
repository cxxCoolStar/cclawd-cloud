package ai.openagent.bootstrap.tool.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具执行线程池配置（V2 方案 12.3：与模型/回合线程池隔离）
 *
 * <p>
 * 显式 AbortPolicy 拒绝策略——不得使用 CallerRunsPolicy 让回合线程
 * 执行长任务（V2 方案 1.3 约束）；队列满时 Invoker 捕获
 * RejectedExecutionException 快速失败返回「服务繁忙」
 * </p>
 */
@Configuration
public class ToolExecutionConfiguration {

    @Bean(name = "toolExecutor", destroyMethod = "shutdownNow")
    public ExecutorService toolExecutor() {
        AtomicInteger counter = new AtomicInteger();
        return new ThreadPoolExecutor(
                2,
                8,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                runnable -> {
                    Thread thread = new Thread(runnable, "tool-exec-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
