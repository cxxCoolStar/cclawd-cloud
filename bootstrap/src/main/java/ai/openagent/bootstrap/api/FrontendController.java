package ai.openagent.bootstrap.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendController {

    @GetMapping({"/agents/default/chat", "/agents/default/chat/"})
    public String defaultChat() {
        return "forward:/agents/default/chat/index.html";
    }

    @GetMapping({"/agents/default/chat/{sessionId}", "/agents/default/chat/{sessionId}/"})
    public String chatSession() {
        return "forward:/agents/default/chat/_/index.html";
    }
}
