package ai.openagent.bootstrap.chat.service;

import ai.openagent.bootstrap.chat.controller.vo.ChatHistoryVO;
import ai.openagent.bootstrap.chat.controller.vo.ChatSessionListVO;
import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.ChatMessageRecord;
import ai.openagent.bootstrap.persistence.ProviderRecord;
import java.util.List;
import java.util.Map;

/**
 * 聊天服务接口
 * <p>
 * 提供聊天回合的开启与流式执行、历史消息与会话查询、事件回放能力。
 * 查询类方法直接返回 VO（装配下沉 Service，Controller 不接触持久化记录）
 * </p>
 */
public interface ChatService {

    /**
     * 开启一个聊天回合：校验会话与消息、定位 agent 与 provider、落库用户消息
     *
     * @param agentId   智能体 ID
     * @param sessionId 会话 ID
     * @param message   用户消息
     * @return 回合上下文（含完整历史，供模型调用）
     */
    Turn beginTurn(String agentId, String sessionId, String message);

    /**
     * 流式执行聊天回合：调用模型网关，逐段广播增量事件，完成后持久化
     * 助手消息与 content/done 事件；异常时发布 error 事件
     *
     * @param turn 由 {@link #beginTurn} 创建的回合上下文
     */
    void stream(Turn turn);

    /**
     * 查询会话历史消息与事件 resume 游标
     */
    ChatHistoryVO history(String agentId, String sessionId);

    /**
     * 查询 agent 下的会话列表
     */
    ChatSessionListVO sessions(String agentId);

    /**
     * 回放指定序号之后的持久化事件（断线重连），已解码为可下发的事件结构
     * （{seq, type, data}，解码失败的事件降级为 error 事件）
     */
    List<Map<String, Object>> replayEventsSince(String agentId, String sessionId, long since);

    /**
     * 聊天回合上下文
     *
     * @param userId    用户 ID
     * @param agent     智能体记录
     * @param provider  模型供应商记录
     * @param sessionId 会话 ID
     * @param messages  截至本回合的完整消息历史（含刚落库的用户消息）
     */
    record Turn(
            String userId,
            AgentRecord agent,
            ProviderRecord provider,
            String sessionId,
            List<ChatMessageRecord> messages) {}
}
