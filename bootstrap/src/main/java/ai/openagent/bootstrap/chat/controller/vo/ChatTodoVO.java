package ai.openagent.bootstrap.chat.controller.vo;

import java.util.List;

/**
 * 会话待办清单视图对象（fastclaw 协议形状：{items, raw}）
 *
 * <p>
 * fastclaw 中该接口读取 agent 维护的 per-session todo.md；本项目 agent
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
