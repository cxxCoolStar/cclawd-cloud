package ai.openagent.bootstrap.config.controller;

import ai.openagent.bootstrap.config.controller.vo.ConfigResponseVO;
import ai.openagent.bootstrap.config.service.ConfigApiService;
import ai.openagent.framework.convention.Result;
import ai.openagent.framework.web.Results;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigApiService configApiService;

    @GetMapping("/api/config")
    public ConfigResponseVO getConfig() {
        return configApiService.getConfig();
    }

    @PostMapping("/api/config")
    public Result<Void> updateConfig(@RequestBody JsonNode body) {
        configApiService.updateConfig(body);
        return Results.success();
    }
}