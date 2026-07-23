package ai.openagent.bootstrap.identity.service;

import ai.openagent.bootstrap.identity.controller.request.CreateApiKeyRequest;
import ai.openagent.bootstrap.identity.controller.vo.ApiKeyListVO;
import ai.openagent.bootstrap.identity.controller.vo.CreatedApiKeyVO;

public interface ApiKeyManagementService {

    ApiKeyListVO list();

    CreatedApiKeyVO create(CreateApiKeyRequest request);

    void delete(String id);
}