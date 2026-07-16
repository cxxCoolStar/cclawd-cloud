package ai.openagent.bootstrap.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping(value = {"/healthz", "/livez", "/readyz"}, produces = MediaType.TEXT_PLAIN_VALUE)
    public String health() {
        return "ok";
    }
}

