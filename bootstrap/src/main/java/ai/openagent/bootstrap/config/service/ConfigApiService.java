package ai.openagent.bootstrap.config.service;

import ai.openagent.bootstrap.config.controller.vo.ConfigResponseVO;
import com.fasterxml.jackson.databind.JsonNode;

public interface ConfigApiService {

    ConfigResponseVO getConfig();

    void updateConfig(JsonNode body);
}