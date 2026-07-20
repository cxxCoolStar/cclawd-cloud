package ai.openagent.bootstrap.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.openagent.bootstrap.memory.config.MemoryProperties;
import ai.openagent.bootstrap.tool.config.ToolProperties;
import ai.openagent.framework.exception.ClientException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MemoryService 单测（落位文件系统，覆盖读写 / 扫描 / 越界 / 检索）
 */
class MemoryServiceTest {

    @Test
    void savesAndLoadsMemoryAndUserFiles(@TempDir Path temp) {
        MemoryService service = service(temp, 32768);
        service.saveMemory("u1", "default", "fact A");
        service.saveUserFile("u1", "default", "pref B");

        assertEquals("fact A", service.loadMemory("default"));
        assertEquals("pref B", service.loadUserFile("default"));
        assertTrue(Files.exists(temp.resolve("default").resolve("MEMORY.md")));
    }

    @Test
    void missingFileReturnsEmptyString(@TempDir Path temp) {
        MemoryService service = service(temp, 32768);
        assertEquals("", service.loadMemory("default"));
    }

    @Test
    void rejectsFilesOverMaxChars(@TempDir Path temp) {
        MemoryService service = service(temp, 10);
        assertThrows(ClientException.class, () -> service.saveMemory("u", "default", "12345678901"));
    }

    @Test
    void searchAcrossFiles(@TempDir Path temp) {
        MemoryService service = service(temp, 32768);
        service.saveMemory("u", "default", "deployment: use Docker\nhost: local");
        service.saveUserFile("u", "default", "name: Alice\nlanguage: zh");

        var hits = service.search("default", "Docker", 10);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).startsWith("MEMORY.md:"));

        var multi = service.search("default", "zh", 10);
        assertEquals(1, multi.size());
        assertTrue(multi.get(0).startsWith("USER.md:"));
    }

    @Test
    void appendHistoryCreatesTimestampedEntry(@TempDir Path temp) {
        MemoryService service = service(temp, 32768);
        service.appendHistory("default", "extracted a fact");
        String history = service.loadHistory("default");
        assertTrue(history.contains("extracted a fact"));
        assertTrue(history.startsWith("- ["));
    }

    private static MemoryService service(Path temp, int maxChars) {
        return new MemoryService(
                new ToolProperties(Duration.ofSeconds(30), 65536, temp.toString(), 1048576, false, 1048576),
                new MemoryProperties(true, true, 5, maxChars, null));
    }
}
