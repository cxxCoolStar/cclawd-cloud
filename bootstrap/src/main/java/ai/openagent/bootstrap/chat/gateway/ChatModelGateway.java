package ai.openagent.bootstrap.chat.gateway;

import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.ChatMessageRecord;
import ai.openagent.bootstrap.persistence.ProviderRecord;
import java.util.List;
import java.util.function.Consumer;

/**
 * 聊天模型网关接口
 * <p>
 * 屏蔽具体模型供应商协议，流式返回模型回答
 * </p>
 */
public interface ChatModelGateway {

    /**
     * 流式调用模型
     *
     * @param provider 供应商配置（apiBase/apiKey/超参）
     * @param agent    智能体（model/systemPrompt）
     * @param messages 完整消息历史
     * @param onDelta  增量文本回调
     * @return 完整回答文本
     * @throws Exception 网络失败、供应商返回错误或空响应
     */
    String stream(
            ProviderRecord provider,
            AgentRecord agent,
            List<ChatMessageRecord> messages,
            Consumer<String> onDelta)
            throws Exception;
}
