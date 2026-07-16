package ai.openagent.bootstrap.persistence;

public record ProviderRecord(
        String id,
        String type,
        String name,
        String apiBase,
        String apiKey,
        String model,
        double temperature,
        int maxTokens) {}
