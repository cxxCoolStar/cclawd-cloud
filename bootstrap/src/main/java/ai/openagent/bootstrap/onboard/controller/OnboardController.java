package ai.openagent.bootstrap.onboard.controller;

import ai.openagent.bootstrap.onboard.controller.request.OnboardRequest;
import ai.openagent.bootstrap.onboard.service.OnboardService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Onboard 初始化控制器（V8 M3）
 *
 * <p>
 * 响应恒为 JSON {@code {"ok": true}}——前端 onboard() 读 body 的
 * ok/error 字段而非 HTTP 状态；校验/系统错误由全局异常处理器输出
 * {@code {"error": "..."}}，前端按 ok 缺省为 falsy 处理。业务语义见
 * {@link OnboardService}
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class OnboardController {

    private final OnboardService onboardService;

    @PostMapping("/api/onboard")
    public Map<String, Boolean> onboard(@RequestBody OnboardRequest request) {
        onboardService.onboard(request);
        return Map.of("ok", true);
    }
}
