package ai.openagent.bootstrap.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * UTF-8 stdio 传输（替代 SDK 的 StdioClientTransport）
 *
 * <p>
 * SDK 0.12.0 的 StdioClientTransport 用平台默认编码读取子进程 stdout
 * （Windows 中文环境为 GBK），而 MCP 协议消息是 UTF-8 JSON——非 ASCII
 * 内容（如中文工具结果）会被解码成乱码。本实现除读写显式 UTF-8 外
 * 与 SDK 行为一致：newline-delimited JSON-RPC、stderr 旁路记录、
 * 关闭时销毁子进程
 * </p>
 */
@Slf4j
public class Utf8StdioClientTransport implements McpClientTransport {

    private final ServerParameters params;
    private final ObjectMapper objectMapper;
    private final Sinks.Many<McpSchema.JSONRPCMessage> inboundSink =
            Sinks.many().unicast().onBackpressureBuffer();
    /**
     * 出站缓冲：sendMessage 可能先于 connect 完成（SDK 同款竞态——
     * initialize 在连接建立前发出），先入队，进程启动后按序写出
     */
    private final Sinks.Many<McpSchema.JSONRPCMessage> outboundSink =
            Sinks.many().unicast().onBackpressureBuffer();

    private volatile boolean closing;
    private Process process;

    public Utf8StdioClientTransport(ServerParameters params, ObjectMapper objectMapper) {
        this.params = params;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> connect(
            java.util.function.Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
        return Mono.fromRunnable(() -> {
                    startProcess();
                    // 对齐 SDK：每条 inbound 消息包装为 Mono 交给客户端 handler
                    inboundSink.asFlux()
                            .flatMap(message ->
                                    Mono.defer(() -> handler.apply(Mono.just(message))).then())
                            .subscribe();
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .then();
    }

    private void startProcess() {
        List<String> command = new ArrayList<>();
        command.add(params.getCommand());
        command.addAll(params.getArgs());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(params.getEnv());
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new IllegalStateException("failed to start mcp stdio server: " + command, error);
        }
        Thread reader = new Thread(this::readLoop, "mcp-stdio-reader");
        reader.setDaemon(true);
        reader.start();
        Thread stderr = new Thread(this::drainStderr, "mcp-stdio-stderr");
        stderr.setDaemon(true);
        stderr.start();
        // 进程就绪后按序消费出站缓冲
        outboundSink.asFlux().subscribe(this::writeMessage);
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
        return Mono.fromRunnable(() -> {
            Sinks.EmitResult result = outboundSink.tryEmitNext(message);
            if (result.isFailure()) {
                throw new IllegalStateException("mcp stdio outbound queue failed: " + result);
            }
        });
    }

    /**
     * 实际写入子进程 stdin（显式 UTF-8）
     */
    private synchronized void writeMessage(McpSchema.JSONRPCMessage message) {
        try {
            byte[] bytes = (objectMapper.writeValueAsString(message) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            process.getOutputStream().write(bytes);
            process.getOutputStream().flush();
        } catch (IOException error) {
            throw new IllegalStateException("mcp stdio write failed", error);
        }
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.fromRunnable(() -> {
            closing = true;
            if (process != null) {
                process.destroy();
            }
            inboundSink.tryEmitComplete();
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unmarshalFrom(Object data, com.fasterxml.jackson.core.type.TypeReference<T> typeReference) {
        if (data instanceof String text) {
            try {
                return objectMapper.readValue(text, typeReference);
            } catch (IOException error) {
                throw new IllegalArgumentException("cannot unmarshal: " + error.getMessage(), error);
            }
        }
        return objectMapper.convertValue(data, typeReference);
    }

    /**
     * 显式 UTF-8 的 inbound 读取循环（SDK 默认编码问题的修复点）
     */
    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closing && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    inboundSink.tryEmitNext(McpSchema.deserializeJsonRpcMessage(objectMapper, line));
                } catch (RuntimeException error) {
                    log.warn("[mcp] 无法解析的 stdio 消息，已跳过：{}", error.getMessage());
                }
            }
        } catch (IOException error) {
            if (!closing) {
                inboundSink.tryEmitError(error);
            }
        }
    }

    private void drainStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closing && (line = reader.readLine()) != null) {
                log.debug("[mcp] stdio server stderr: {}", line);
            }
        } catch (IOException ignored) {
            // 子进程退出
        }
    }
}
