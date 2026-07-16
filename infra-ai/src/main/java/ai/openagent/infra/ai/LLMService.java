package ai.openagent.infra.ai;

import ai.openagent.infra.ai.model.ModelRequest;

public interface LLMService {

    ModelCallHandle streamChat(ModelRequest request, ModelEventListener listener);
}

