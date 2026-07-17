package ai.openagent.infra.ai;

import ai.openagent.infra.ai.model.ModelRequest;
import ai.openagent.infra.ai.model.ModelResponse;

/**
 * 模型端口（V2 方案 7.2）
 *
 * <p>
 * 同步表达一次完整的流式模型调用：增量经 listener 实时回调，
 * 方法返回聚合后的最终结果（文本完成或工具请求两类，见
 * {@link ModelResponse}）。线程模型由调用方（AgentRunCoordinator /
 * ChatTurnCoordinator 的执行线程池）负责，不泄漏进本端口
 * </p>
 */
public interface LLMService {

    /**
     * 流式调用模型并聚合最终结果
     *
     * @param request  请求（含供应商配置、消息历史与可用工具）
     * @param listener 增量事件回调（content/reasoning delta）
     * @return 聚合后的最终结果
     * @throws ai.openagent.framework.exception.RemoteException 网络失败或供应商返回错误
     */
    ModelResponse stream(ModelRequest request, ModelEventListener listener);
}
