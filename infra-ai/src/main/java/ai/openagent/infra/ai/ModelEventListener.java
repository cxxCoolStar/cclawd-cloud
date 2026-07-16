package ai.openagent.infra.ai;

import ai.openagent.infra.ai.model.ModelEvent;

@FunctionalInterface
public interface ModelEventListener {

    void onEvent(ModelEvent event);
}

