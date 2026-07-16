package ai.openagent.bootstrap.persistence;

import ai.openagent.bootstrap.config.ModelSettings;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer implements ApplicationRunner {

    private final OpenAgentStore store;
    private final ModelSettings modelSettings;

    public DatabaseInitializer(OpenAgentStore store, ModelSettings modelSettings) {
        this.store = store;
        this.modelSettings = modelSettings;
    }

    @Override
    public void run(ApplicationArguments args) {
        store.seedDefaults(modelSettings);
    }
}
