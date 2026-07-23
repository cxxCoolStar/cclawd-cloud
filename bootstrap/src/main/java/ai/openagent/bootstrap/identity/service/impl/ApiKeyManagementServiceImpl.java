package ai.openagent.bootstrap.identity.service.impl;

import ai.openagent.bootstrap.identity.controller.request.CreateApiKeyRequest;
import ai.openagent.bootstrap.identity.controller.vo.ApiKeyListVO;
import ai.openagent.bootstrap.identity.controller.vo.CreatedApiKeyVO;
import ai.openagent.bootstrap.identity.service.ApiKeyManagementService;
import ai.openagent.bootstrap.identity.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApiKeyManagementServiceImpl implements ApiKeyManagementService {

    private final ApiKeyService apiKeyService;

    @Override
    public ApiKeyListVO list() {
        return new ApiKeyListVO(apiKeyService.list());
    }

    @Override
    public CreatedApiKeyVO create(CreateApiKeyRequest request) {
        ApiKeyService.CreatedApiKey created = apiKeyService.create(request.name(), request.agentIds());
        return new CreatedApiKeyVO(created.apikey(), created.token());
    }

    @Override
    public void delete(String id) {
        apiKeyService.delete(id);
    }
}