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
 * SSE 帧写入能力，统一协议编码细节，避免各 Controller 重复手写
 * {@code "data: ..."} 拼装逻辑。协议对齐 fastclaw：
 * <ul>
 *   <li>带序号事件输出 {@code id: <seq>} 行，供浏览器 EventSource 断线重连时
 *       通过 {@code Last-Event-ID} 头恢复进度</li>
 *   <li>注释帧（{@code ": ok"} / {@code ": ping"}）用于连接建立确认与保活心跳</li>
 * </ul>
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
     * @param seq   事件序号；不小于 0 时输出 {@code id:} 行供断线重连定位
     * @param event 要发送的事件对象
     * @throws IOException 客户端断开或底层流写入失败
     */
    public synchronized void send(long seq, Object event) throws IOException {
        StringBuilder frame = new StringBuilder();
        if (seq >= 0) {
            frame.append("id: ").append(seq).append('\n');
        }
        frame.append("data: ").append(objectMapper.writeValueAsString(event)).append("\n\n");
        output.write(frame.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /**
     * 发送一帧不带序号的 SSE 数据
     *
     * @param event 要发送的事件对象
     * @throws IOException 客户端断开或底层流写入失败
     */
    public void send(Object event) throws IOException {
        send(-1, event);
    }

    /**
     * 发送一帧 SSE 注释（如 {@code ": ok"} 连接确认、{@code ": ping"} 心跳）
     *
     * @param text 注释内容
     * @throws IOException 客户端断开或底层流写入失败
     */
    public synchronized void comment(String text) throws IOException {
        output.write((": " + text + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
