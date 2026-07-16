package ai.openagent.infra.ai;

import ai.openagent.infra.ai.model.ModelRequest;

public interface ChatClient {

    boolean supports(String providerType);

    ModelCallHandle stream(ModelRequest request, ModelEventListener listener);
}

