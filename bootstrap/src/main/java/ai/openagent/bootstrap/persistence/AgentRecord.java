package ai.openagent.bootstrap.persistence;

public record AgentRecord(
        String id,
        String userId,
        String name,
        String description,
        String providerId,
        String model,
        String systemPrompt,
        long createdAt,
        long updatedAt) {}
