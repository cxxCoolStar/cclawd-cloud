package ai.openagent.framework.convention;

import java.io.Serial;
import java.io.Serializable;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 全局统一返回结果对象
 *
 * <p>
 * 用于规范化所有 API 接口的返回格式，确保前后端交互的一致性。
 * 除 SSE / 流式接口外，所有接口返回都应使用此对象包装，
 * 通过 {@link ai.openagent.framework.web.Results} 静态工厂构建。
 * </p>
 *
 * @param <T> 响应数据的类型
 */
@Data
@Accessors(chain = true)
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 成功状态码
     */
    public static final String SUCCESS_CODE = "0";

    /**
     * 状态码
     * <p>
     * {@code "0"} 表示成功，其他值表示各类错误或异常情况
     * </p>
     */
    private String code;

    /**
     * 响应消息
     * <p>
     * 成功时可为空，失败时为错误原因说明
     * </p>
     */
    private String message;

    /**
     * 响应数据
     * <p>
     * 接口返回的业务数据，类型由泛型 T 指定。请求失败时可能为 {@code null}
     * </p>
     */
    private T data;

    /**
     * 请求追踪 ID
     * <p>
     * 用于链路追踪和问题排查
     * </p>
     */
    private String requestId;

    /**
     * 判断请求是否成功
     *
     * @return 状态码为 {@link #SUCCESS_CODE} 时返回 {@code true}
     */
    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }
}
