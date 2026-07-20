package ai.openagent.bootstrap.chat.controller.vo;

import java.util.List;

/**
 * 会话历史视图对象
 *
 * @param history        历史消息列表
 * @param latestEventSeq 最新事件序号——前端拿它作为 /api/chat/subscribe
 *                       的 since 游标，页面刷新后只补拉未渲染的增量事件
 */
public record ChatHistoryVO(List<ChatMessageVO> history, long latestEventSeq) {}
