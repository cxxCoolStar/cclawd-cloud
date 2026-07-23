package ai.openagent.bootstrap.onboard.controller;

import ai.openagent.bootstrap.onboard.controller.request.OnboardRequest;
import ai.openagent.bootstrap.onboard.service.OnboardService;
import ai.openagent.framework.convention.Result;
import ai.openagent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Onboard 初始化控制器。
 *
 * <p>
 * 后端接口按统一 {@link Result} 响应；前端 API 适配层继续对页面暴露
 * ok/error 形状，避免 onboard 页面结构跟随接口迁移。
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class OnboardController {

    private final OnboardService onboardService;

    @PostMapping("/api/onboard")
    public Result<Void> onboard(@RequestBody OnboardRequest request) {
        onboardService.onboard(request);
        return Results.success();
    }
}
