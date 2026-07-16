package ai.openagent.bootstrap.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.openagent.bootstrap.OpenAgentApplication;
import ai.openagent.bootstrap.identity.IdentityConstant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 会话仓储并发回归：seq 由 INSERT 内 COALESCE(MAX(seq),0)+1 原子分配，
 * 并发追加不得产生重复或空洞（历史缺陷：synchronized+@Transactional 混搭，
 * JVM 锁不保护跨实例且锁边界早于事务提交）
 */
@SpringBootTest(
        classes = OpenAgentApplication.class,
        properties = {
            "spring.datasource.url=jdbc:sqlite:target/chat-session-repository-test.db",
            "openagent.model.api-key=test-key",
            "openagent.model.name=test-model"
        })
class ChatSessionRepositoryTest {

    private static final String USER_ID = IdentityConstant.LOCAL_USER_ID;
    private static final String AGENT_ID = "default";

    @Autowired
    private ChatSessionRepository repository;

    @Test
    void concurrentAppendsProduceContiguousUniqueSequences() throws Exception {
        String sessionId = "seq-race-" + UUID.randomUUID();
        repository.ensureSession(USER_ID, AGENT_ID, sessionId, "first");

        int writers = 8;
        int perWriter = 5;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Callable<Void>> tasks = java.util.stream.IntStream.range(0, writers)
                    .<Callable<Void>>mapToObj(w -> () -> {
                        for (int i = 0; i < perWriter; i++) {
                            repository.appendMessage(
                                    USER_ID, AGENT_ID, sessionId, "user", "m-" + w + "-" + i, "test", "test-model");
                            repository.appendEvent(USER_ID, AGENT_ID, sessionId, "content", "{}");
                        }
                        return null;
                    })
                    .toList();
            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        int total = writers * perWriter;
        List<ChatMessageRecord> messages = repository.listMessages(USER_ID, AGENT_ID, sessionId);
        assertEquals(total, messages.size());
        Set<Long> messageSeqs = messages.stream().map(ChatMessageRecord::seq).collect(Collectors.toSet());
        assertEquals(total, messageSeqs.size(), "消息 seq 不得重复");
        assertEquals(1L, messages.get(0).seq(), "seq 从 1 开始");
        assertEquals((long) total, messages.get(total - 1).seq(), "seq 必须连续无空洞");

        List<SessionEventRecord> events = repository.listEventsSince(USER_ID, AGENT_ID, sessionId, -1);
        assertEquals(total, events.size());
        Set<Long> eventSeqs = events.stream().map(SessionEventRecord::seq).collect(Collectors.toSet());
        assertEquals(total, eventSeqs.size(), "事件 seq 不得重复");
        assertEquals((long) total, repository.latestEventSequence(USER_ID, AGENT_ID, sessionId));
    }
}
