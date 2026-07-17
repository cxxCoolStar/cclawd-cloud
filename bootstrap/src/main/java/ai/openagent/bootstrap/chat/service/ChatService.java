package ai.openagent.bootstrap.chat.service;

import ai.openagent.bootstrap.chat.controller.vo.ChatHistoryVO;
import ai.openagent.bootstrap.chat.controller.vo.ChatSessionListVO;
import java.util.List;
import java.util.Map;

/**
 * 聊天查询服务接口
 *
 * <p>
 * V2 起回合执行统一由 agentrun 域的 AgentRunCoordinator + AgentKernel
 * 承载（无工具聊天是工具列表为空的退化情形），本服务收敛为历史消息、
 * 会话列表与事件回放的查询能力。查询类方法直接返回 VO（装配下沉
 * Service，Controller 不接触持久化记录）
 * </p>
 */
public interface ChatService {

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
}
