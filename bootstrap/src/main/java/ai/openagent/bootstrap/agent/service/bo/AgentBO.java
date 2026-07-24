package ai.openagent.bootstrap.agent.service.bo;

public record AgentBO(
        String id,
        String userId,
        String name,
        String description,
        String providerId,
        String model,
        String systemPrompt,
        long createdAt,
        long updatedAt) {}
