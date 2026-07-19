package ai.openagent.bootstrap.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.openagent.agent.AgentRunStatus;
import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.identity.IdentityConstant;
import ai.openagent.bootstrap.persistence.AgentRepository;
import ai.openagent.bootstrap.persistence.AgentRunRecord;
import ai.openagent.bootstrap.persistence.AgentRunRepository;
import ai.openagent.bootstrap.persistence.AgentToolRepository;
import ai.openagent.bootstrap.persistence.ChatSessionRepository;
import ai.openagent.bootstrap.persistence.ConfigRepository;
import ai.openagent.bootstrap.persistence.DataSeeder;
import ai.openagent.bootstrap.persistence.ProviderRepository;
import ai.openagent.bootstrap.persistence.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Agent 创建/删除 + Onboard 接口集成测试（V8 M3）
 *
 * <p>
 * 覆盖：创建字段校验与缺省回落、级联删除（sessions/messages/events/
 * runs/tools/configs 键）、默认 agent 拒删（400）、未知 agent 404、
 * onboard 写入供应商配置与创建首个 agent、空 provider 跳过写入。
 * 测试库每次运行前删除重建（V9 M2 起 onboard 建号依赖"全新部署"语义）
 * </p>
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/agent-lifecycle-test.db",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model",
            "openagent.tools.workspace-root=target/agent-lifecycle-ws"
        })
@AutoConfigureMockMvc
class AgentLifecycleEndpointsTest {

    static {
        new java.io.File("target/agent-lifecycle-test.db").delete();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentToolRepository agentToolRepository;

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Autowired
    private ConfigRepository configRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void createsAgentWithDefaultsAndValidatesName() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"researcher\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agent.name").value("researcher"))
                .andExpect(jsonPath("$.agent.model").value("test-model"))
                .andReturn();
        JsonNode agent = objectMapper.readTree(result.getResponse().getContentAsString()).path("agent");
        String id = agent.path("id").asText();
        assertTrue(id.startsWith("agt_"), "id 应为 agt_ 前缀随机 hex");
        assertTrue(agentRepository.findById(id).isPresent());
        // 内置工具默认配置已补种（与 DataSeeder 同源）
        assertTrue(!agentToolRepository.listByAgent(id).isEmpty());

        // name 缺失 → 400
        mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"no name\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletesAgentWithCascadeAndRejectsDefault() throws Exception {
        // 准备一个带全量关联数据的 agent
        MvcResult result = mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"doomed\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String id = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("agent").path("id").asText();
        String sessionId = "doomed-" + UUID.randomUUID();
        sessionRepository.ensureSession(IdentityConstant.LOCAL_USER_ID, id, sessionId, "hi");
        sessionRepository.appendMessage(IdentityConstant.LOCAL_USER_ID, id, sessionId, "user", "hi", "", "");
        long now = System.currentTimeMillis();
        runRepository.insert(new AgentRunRecord(
                UUID.randomUUID().toString(), IdentityConstant.LOCAL_USER_ID, id, sessionId,
                AgentRunStatus.COMPLETED, 0, null, null, now, now, now, now));
        String skillKey = "skills.agentEntries." + id;
        configRepository.upsert(ConfigRepository.SCOPE_AGENT, id, skillKey, "{}");

        // 默认 agent 拒删（400），未知 agent 404
        mockMvc.perform(delete("/api/agents/default")).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/agents/no-such-agent")).andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/agents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        // 级联清理：agents / sessions / messages / events / runs / tools / configs 键
        assertTrue(agentRepository.findById(id).isEmpty());
        assertTrue(sessionRepository
                .listMessages(IdentityConstant.LOCAL_USER_ID, id, sessionId).isEmpty());
        assertTrue(sessionRepository
                .listEventsSince(IdentityConstant.LOCAL_USER_ID, id, sessionId, -1).isEmpty());
        assertTrue(sessionRepository
                .listSessions(IdentityConstant.LOCAL_USER_ID, id).isEmpty());
        assertTrue(runRepository
                .listBySession(IdentityConstant.LOCAL_USER_ID, id, sessionId, 10).isEmpty());
        assertTrue(agentToolRepository.listByAgent(id).isEmpty());
        assertTrue(configRepository.get(ConfigRepository.SCOPE_AGENT, id, skillKey).isEmpty());
        // 默认 agent 及其数据不受影响
        assertTrue(agentRepository.exists(DataSeeder.DEFAULT_AGENT_ID));
    }

    @Test
    void onboardWritesProviderAndCreatesFirstAgent() throws Exception {
        String agentName = "onboarded-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/onboard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "alice", "email": "a@b.c", "password": "secret123",
                                  "provider": "deepseek", "apiBase": "https://api.deepseek.com",
                                  "apiKey": "sk-onboard-test", "model": "deepseek-v4-pro",
                                  "agentName": "%s"
                                }
                                """.formatted(agentName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        // 默认供应商连接配置已写入；默认 agent 模型同步
        var provider = providerRepository.findById(DataSeeder.DEFAULT_PROVIDER_ID).orElseThrow();
        assertEquals("sk-onboard-test", provider.apiKey());
        assertEquals("https://api.deepseek.com", provider.apiBase());
        assertEquals("deepseek-v4-pro", provider.model());
        assertEquals("deepseek-v4-pro",
                agentRepository.findById(DataSeeder.DEFAULT_AGENT_ID).orElseThrow().model());
        // V9 M2：全新部署 onboard 建号，首个业务 agent 归属新建账号
        String ownerId = userRepository.findByUsername("alice").orElseThrow().id();
        assertTrue(agentRepository.listByUser(ownerId).stream()
                .anyMatch(agent -> agent.name().equals(agentName)));
    }

    @Test
    void onboardSkipsProviderWriteWhenBlank() throws Exception {
        String before = providerRepository.findById(DataSeeder.DEFAULT_PROVIDER_ID)
                .orElseThrow().apiKey();
        mockMvc.perform(post("/api/onboard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"bob\", \"provider\": \"\", \"apiKey\": \"\", \"agentName\": \"default\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        // provider/apiKey 为空 → 供应商配置不动；agentName=default 跳过创建
        assertEquals(before,
                providerRepository.findById(DataSeeder.DEFAULT_PROVIDER_ID).orElseThrow().apiKey());
        assertEquals(1,
                agentRepository.listByUser(IdentityConstant.LOCAL_USER_ID).stream()
                        .filter(agent -> agent.id().equals(DataSeeder.DEFAULT_AGENT_ID))
                        .count());
    }
}
