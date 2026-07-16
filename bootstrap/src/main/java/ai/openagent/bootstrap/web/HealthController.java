package ai.openagent.bootstrap.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康探针控制器
 *
 * <p>
 * 豁免 VO / 统一协议规范：探针必须返回纯文本 {@code ok}，
 * 保持与 K8s liveness/readiness 及原 Go 服务的探针协议兼容
 * </p>
 */
@RestController
public class HealthController {

    /**
     * 存活/就绪探针
     */
    @GetMapping(value = {"/healthz", "/livez", "/readyz"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String health() {
        return "ok";
    }
}
