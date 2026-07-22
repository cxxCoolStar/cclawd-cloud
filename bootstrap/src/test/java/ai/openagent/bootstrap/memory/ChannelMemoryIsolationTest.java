package ai.openagent.bootstrap.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.openagent.agent.AgentConversationScope;
import ai.openagent.bootstrap.memory.config.MemoryProperties;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelMemoryIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    void externalIdsNeverBecomePathSegmentsAndScopesDoNotShareMemory() {
        ToolProperties tools = new ToolProperties(java.time.Duration.ofSeconds(1), 1024, tempDir.toString(), 1024, false, 1024);
        MemoryProperties properties = new MemoryProperties(true, false, 5, 32768, null);
        MemoryService service = new MemoryService(tools, properties);
        AgentConversationScope first = new AgentConversationScope(
                "wechat", "account", "../../unsafe", "chatter-a", "scope-a");
        AgentConversationScope second = new AgentConversationScope(
                "wechat", "account", "../../unsafe", "chatter-b", "scope-b");

        service.saveMemory("owner", "default", first, "first-only");
        service.saveMemory("owner", "default", second, "second-only");

        assertEquals("first-only", service.loadMemory("default", first));
        assertEquals("second-only", service.loadMemory("default", second));
        assertNotEquals(service.memoryHome("default", first), service.memoryHome("default", second));
        assertEquals(tempDir.resolve("default/chats/wechat/scope-a"), service.memoryHome("default", first));
        assertEquals(tempDir.resolve("default/chats/wechat/scope-a/logs"),
                service.historyLogHome("default", first));
        assertEquals(tempDir.resolve("default/memory/logs"),
                service.historyLogHome("default", null));

        AgentConversationScope shared = new AgentConversationScope(
                "wechat", "account", "chat", "chatter", "shared-scope", true);
        assertEquals(tempDir.resolve("default"), service.memoryHome("default", shared));
        assertEquals(tempDir.resolve("default/memory/logs"),
                service.historyLogHome("default", shared));
    }

    @Test
    void rejectsOversizedExternalIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new AgentConversationScope(
                "wechat", "a".repeat(256), "chat", "chatter", "scope"));
    }
}
