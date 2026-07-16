package ai.openagent.bootstrap.status.controller;

import ai.openagent.bootstrap.status.controller.vo.PlatformStatusVO;
import ai.openagent.bootstrap.status.service.PlatformStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台状态控制器
 * 提供平台运行状态查询接口
 */
@RestController
@RequiredArgsConstructor
public class StatusController {

    private final PlatformStatusService statusService;

    /**
     * 查询平台当前状态
     */
    @GetMapping("/api/status")
    public PlatformStatusVO status() {
        return statusService.currentStatus();
    }
}
