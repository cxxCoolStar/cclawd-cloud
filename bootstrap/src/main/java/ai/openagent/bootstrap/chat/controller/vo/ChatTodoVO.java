package ai.openagent.bootstrap.chat.controller.vo;

import java.util.List;

/**
 * 会话待办清单视图对象，包含解析后的待办项列表和原始文本
 *
 * <p>
 * 该接口设计用于读取 agent 维护的 per-session todo.md；当前 agent
 * 工作区能力尚未落地，暂返回空清单（前端在 items 为空时隐藏面板）
 * </p>
 *
 * @param items 解析后的待办项
 * @param raw   todo.md 原文
 */
public record ChatTodoVO(List<Object> items, String raw) {

    /**
     * 空待办清单
     */
    public static ChatTodoVO empty() {
        return new ChatTodoVO(List.of(), "");
    }
}
