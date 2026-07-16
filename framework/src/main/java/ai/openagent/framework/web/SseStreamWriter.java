package ai.openagent.framework.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;

/**
 * SSE（Server-Sent Events）流式写入器
 *
 * <p>
 * 对基于 {@code StreamingResponseBody} 的原始输出流进行封装，提供线程安全的
 * SSE 帧写入能力（数据帧 + 心跳帧），统一协议编码细节，避免各 Controller
 * 重复手写 {@code "data: ..."} 拼装逻辑。
 * </p>
 *
 * <p>
 * 注意：本项目 SSE 采用 {@code StreamingResponseBody} 而非 Spring 的
 * {@code SseEmitter}，因此该封装面向 {@link OutputStream}；
 * 若后续迁移到 SseEmitter，可在此包内补充对应的 Sender 封装
 * </p>
 */
@Slf4j
public class SseStreamWriter {

    private final OutputStream output;

    private final ObjectMapper objectMapper;

    public SseStreamWriter(OutputStream output, ObjectMapper objectMapper) {
        this.output = output;
        this.objectMapper = objectMapper;
    }

    /**
     * 发送一帧 SSE 数据（对象将被序列化为 JSON）
     *
     * @param event 要发送的事件对象
     * @throws IOException 客户端断开或底层流写入失败
     */
    public synchronized void send(Object event) throws IOException {
        String frame = "data: " + objectMapper.writeValueAsString(event) + "\n\n";
        output.write(frame.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /**
     * 发送一帧 SSE 注释作为心跳，维持连接活跃
     *
     * @throws IOException 客户端断开或底层流写入失败
     */
    public synchronized void heartbeat() throws IOException {
        output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
