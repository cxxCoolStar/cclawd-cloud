package ai.openagent.bootstrap.api;

import ai.openagent.bootstrap.status.PlatformStatusService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {

    private final PlatformStatusService statusService;

    public StatusController(PlatformStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return statusService.currentStatus();
    }
}

