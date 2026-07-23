package ai.openagent.bootstrap.config.service.impl;

import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.config.ConfigService;
import ai.openagent.bootstrap.config.ModelSettings;
import ai.openagent.bootstrap.config.controller.vo.ConfigResponseVO;
import ai.openagent.bootstrap.config.service.ConfigApiService;
import ai.openagent.bootstrap.sandbox.config.SandboxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConfigApiServiceImpl implements ConfigApiService {

    private final ConfigService configService;
    private final AgentService agentService;
    private final ModelSettings modelSettings;
    private final SandboxProperties sandboxProperties;

    @Override
    public ConfigResponseVO getConfig() {
        ConfigService.AgentDefaults defaults = configService.agentDefaults();
        return new ConfigResponseVO(
                Map.of(
                        modelSettings.provider(),
                        new ConfigResponseVO.ProviderVO(
                                ConfigService.maskSecret(modelSettings.apiKey()), modelSettings.apiBase())),
                new ConfigResponseVO.AgentsVO(new ConfigResponseVO.AgentDefaultsVO(
                        defaults.model(), defaults.maxTokens(), defaults.temperature(), defaults.maxToolIterations())),
                Map.of(),
                new ConfigResponseVO.StorageVO("sqlite", null),
                new ConfigResponseVO.SandboxVO(
                        configService.sandboxEnabled(), "docker", sandboxProperties.image(), sandboxProperties.image()),
                configService.prefs(),
                new ConfigResponseVO.SkillsVO(
                        configService.maskedSkillEntries(configService.skillEntries()), maskedAgentEntries()),
                new ConfigResponseVO.HooksVO(false),
                new ConfigResponseVO.MetaVO(modelSettings.name(), ZoneId.systemDefault().getId()));
    }

    @Override
    public void updateConfig(JsonNode body) {
        JsonNode agents = body.get("agents");
        if (agents != null && agents.get("defaults") != null) {
            configService.patchAgentDefaults(agents.get("defaults"));
        }
        JsonNode skills = body.get("skills");
        if (skills != null) {
            if (skills.get("entries") != null) {
                configService.patchSkillEntries(null, skills.get("entries"));
            }
            JsonNode agentEntries = skills.get("agentEntries");
            if (agentEntries != null) {
                agentEntries.fields().forEachRemaining(agentEntry -> {
                    agentService.requireAccess(agentEntry.getKey());
                    configService.patchSkillEntries(agentEntry.getKey(), agentEntry.getValue());
                });
            }
        }
        if (body.get("prefs") != null) {
            configService.patchPrefs(body.get("prefs"));
        }
        JsonNode sandbox = body.get("sandbox");
        if (sandbox != null && sandbox.get("enabled") != null && sandbox.get("enabled").isBoolean()) {
            configService.patchSandbox(sandbox.get("enabled").asBoolean());
        }
    }

    private Map<String, Map<String, ConfigService.SkillEntry>> maskedAgentEntries() {
        Map<String, Map<String, ConfigService.SkillEntry>> masked = new LinkedHashMap<>();
        configService.agentSkillEntries().forEach(
                (agentId, entries) -> masked.put(agentId, configService.maskedSkillEntries(entries)));
        return masked;
    }
}