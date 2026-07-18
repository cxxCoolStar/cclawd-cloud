package ai.openagent.bootstrap.config.controller;

import ai.openagent.bootstrap.config.ConfigService;
import ai.openagent.bootstrap.config.ModelSettings;
import ai.openagent.bootstrap.config.controller.vo.ConfigResponseVO;
import ai.openagent.bootstrap.sandbox.config.SandboxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.ZoneId;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局配置接口（V7 方案 3.2）
 *
 * <p>
 * GET 返回前端 ConfigResponse 形状（secret 打码）；POST 为 PATCH 深合并
 * 语义，仅处理出现的子树：{@code agents.defaults} / {@code skills.entries} /
 * {@code skills.agentEntries} / {@code prefs} / {@code sandbox}（仅 enabled 可写）
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;
    private final ModelSettings modelSettings;
    private final SandboxProperties sandboxProperties;

    /**
     * 读取全局配置（密钥打码回显）
     */
    @GetMapping("/api/config")
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
                        configService.maskedSkillEntries(configService.skillEntries()),
                        maskedAgentEntries()),
                new ConfigResponseVO.HooksVO(false),
                new ConfigResponseVO.MetaVO(modelSettings.name(), ZoneId.systemDefault().getId()));
    }

    /**
     * PATCH 全局配置：仅处理出现的子树，未出现的保持不动
     */
    @PostMapping("/api/config")
    public Map<String, Boolean> updateConfig(@RequestBody JsonNode body) {
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
                agentEntries.fields().forEachRemaining(agentEntry ->
                        configService.patchSkillEntries(agentEntry.getKey(), agentEntry.getValue()));
            }
        }
        if (body.get("prefs") != null) {
            configService.patchPrefs(body.get("prefs"));
        }
        JsonNode sandbox = body.get("sandbox");
        if (sandbox != null && sandbox.get("enabled") != null && sandbox.get("enabled").isBoolean()) {
            configService.patchSandbox(sandbox.get("enabled").asBoolean());
        }
        return Map.of("ok", true);
    }

    private Map<String, Map<String, ConfigService.SkillEntry>> maskedAgentEntries() {
        Map<String, Map<String, ConfigService.SkillEntry>> masked = new java.util.LinkedHashMap<>();
        configService
                .agentSkillEntries()
                .forEach((agentId, entries) -> masked.put(agentId, configService.maskedSkillEntries(entries)));
        return masked;
    }
}
