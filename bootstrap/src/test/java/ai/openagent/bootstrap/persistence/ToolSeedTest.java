package ai.openagent.bootstrap.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.tool.ToolCatalog;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 种子数据回归：默认 Agent 的工具配置按 ToolCatalog 补种，
 * 默认启用集与 V2 方案 3.1 表格一致，且不覆盖用户显式启停
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            // 库名带随机数：write_file 启停状态会写库，跨构建复用同一文件会污染断言
            "spring.datasource.url=jdbc:sqlite:target/tool-seed-test-${random.int(100000)}.db",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model"
        })
class ToolSeedTest {

    @Autowired
    private AgentToolRepository toolRepository;

    @Autowired
    private DataSeeder dataSeeder;

    @Test
    void seedsCatalogDefaultsAndPreservesExplicitUserChoiceOnReseed() {
        // 1. 初始种子：数量与默认启用集与 ToolCatalog 一致
        List<AgentToolRecord> tools = toolRepository.listByAgent(DataSeeder.DEFAULT_AGENT_ID);
        assertEquals(ToolCatalog.BUILTIN_TOOLS.size(), tools.size());

        Set<String> enabled = toolRepository.listEnabledToolNames(DataSeeder.DEFAULT_AGENT_ID).stream()
                .collect(Collectors.toSet());
        assertEquals(Set.of("get_current_time", "calculator", "list_dir", "read_file"), enabled);

        // 2. 用户显式启用高风险工具后再次种子（模拟应用重启）：不得被覆盖
        toolRepository.upsert(DataSeeder.DEFAULT_AGENT_ID, "write_file", true, "{}");
        dataSeeder.seed();
        assertTrue(
                toolRepository.find(DataSeeder.DEFAULT_AGENT_ID, "write_file").orElseThrow().enabled(),
                "补种不得覆盖用户显式启停");
    }
}
