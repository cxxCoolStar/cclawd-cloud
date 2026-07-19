package ai.openagent.bootstrap.agentrun.trace;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行轨迹控制器（EVALUATION_PLAN.md Phase 1.2）：单次运行的
 * 完整 Trace 查询，人工排查与 Phase 6 Dashboard 的前置
 */
@RestController
@RequiredArgsConstructor
public class TraceController {

    private final TraceService traceService;

    /**
     * 查询运行轨迹（归属校验在 service 入口，越权 404）
     */
    @GetMapping("/api/runs/{runId}/trace")
    public TraceVO getTrace(@PathVariable String runId) {
        return traceService.export(runId);
    }
}
