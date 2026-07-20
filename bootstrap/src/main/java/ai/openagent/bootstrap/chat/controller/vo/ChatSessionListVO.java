package ai.openagent.bootstrap.chat.controller.vo;

import java.util.List;

/**
 * 会话列表视图对象（包装会话摘要列表）
 *
 * @param sessions 会话摘要列表
 */
public record ChatSessionListVO(List<ChatSessionVO> sessions) {}
