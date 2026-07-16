package ai.openagent.bootstrap.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端页面转发控制器
 *
 * <p>
 * 豁免 REST 规范：非 API 接口，仅将 Next.js 静态导出的动态路由
 * forward 到对应的 index.html
 * </p>
 */
@Controller
public class FrontendController {

    /**
     * 默认聊天页
     */
    @GetMapping({"/agents/default/chat", "/agents/default/chat/"})
    public String defaultChat() {
        return "forward:/agents/default/chat/index.html";
    }

    /**
     * 指定会话的聊天页（Next.js 动态段导出为 _ 目录）
     */
    @GetMapping({"/agents/default/chat/{sessionId}", "/agents/default/chat/{sessionId}/"})
    public String chatSession() {
        return "forward:/agents/default/chat/_/index.html";
    }
}
