package ai.openagent.bootstrap.chat;

import ai.openagent.bootstrap.persistence.AgentRecord;
import ai.openagent.bootstrap.persistence.ChatMessageRecord;
import ai.openagent.bootstrap.persistence.ProviderRecord;
import java.util.List;
import java.util.function.Consumer;

public interface ChatModelGateway {

    String stream(
            ProviderRecord provider,
            AgentRecord agent,
            List<ChatMessageRecord> messages,
            Consumer<String> onDelta)
            throws Exception;
}
