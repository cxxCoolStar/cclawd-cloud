package ai.openagent.bootstrap.identity.service.impl;

import ai.openagent.bootstrap.agent.service.AgentService;
import ai.openagent.bootstrap.identity.AuthConstant;
import ai.openagent.bootstrap.identity.controller.vo.ApiKeyVO;
import ai.openagent.bootstrap.identity.service.ApiKeyService;
import ai.openagent.bootstrap.persistence.ApiKeyRecord;
import ai.openagent.bootstrap.persistence.ApiKeyRepository;
import ai.openagent.bootstrap.persistence.UserRepository;
import ai.openagent.framework.errorcode.BaseErrorCode;
import ai.openagent.framework.exception.ClientException;
import ai.openagent.framework.exception.ServiceException;
import ai.openagent.framework.identity.RequestContext;
import ai.openagent.framework.identity.RequestIdentity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * API Key 服务实现
 */
@Service
@RequiredArgsConstructor
public class ApiKeyServiceImpl implements ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final UserRepository userRepository;
    private final AgentService agentService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public CreatedApiKey create(String name, List<String> agentIds) {
        if (name == null || name.isBlank()) {
            throw new ClientException("name required", BaseErrorCode.PARAM_VERIFY_ERROR);
        }
        String userId = RequestContext.requireUserId();
        List<String> scope = agentIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(agentIds));
        // 绑定子集逐一校验归属：不属于自己的 agent 按不存在处理（不暴露存在性）
        for (String agentId : scope) {
            agentService.requireAccess(agentId);
        }
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = AuthConstant.API_KEY_PREFIX + HexFormat.of().formatHex(bytes);
        ApiKeyRecord record = new ApiKeyRecord(
                "key_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                userId,
                name.trim(),
                hash(token),
                writeJson(scope),
                System.currentTimeMillis(),
                null);
        apiKeyRepository.insert(record);
        return new CreatedApiKey(toVO(record, scope), token);
    }

    @Override
    public List<ApiKeyVO> list() {
        return apiKeyRepository.listByUser(RequestContext.requireUserId()).stream()
                .map(record -> toVO(record, readScope(record.agentIdsJson())))
                .toList();
    }

    @Override
    public void delete(String id) {
        if (!apiKeyRepository.delete(id, RequestContext.requireUserId())) {
            throw new ClientException("api key not found", BaseErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    @Override
    public Optional<RequestIdentity> authenticate(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(AuthConstant.API_KEY_PREFIX)) {
            return Optional.empty();
        }
        Optional<ApiKeyRecord> record = apiKeyRepository.findByHash(hash(rawKey));
        if (record.isEmpty()) {
            return Optional.empty();
        }
        return userRepository
                .findById(record.get().userId())
                .filter(user -> "active".equals(user.status()))
                .map(user -> {
                    apiKeyRepository.touchLastUsed(record.get().id(), System.currentTimeMillis());
                    Set<String> scope = new LinkedHashSet<>(readScope(record.get().agentIdsJson()));
                    return new RequestIdentity(
                            user.id(), user.id(), user.id(), user.role(),
                            AuthConstant.AUTH_METHOD_API_KEY, scope);
                });
    }

    /**
     * 装配视图：key 字段打码（散列前缀仅作展示，不可用于认证）
     */
    private ApiKeyVO toVO(ApiKeyRecord record, List<String> scope) {
        return new ApiKeyVO(
                record.id(),
                record.userId(),
                record.name(),
                AuthConstant.API_KEY_PREFIX + record.keyHash().substring(0, 4) + "****",
                scope.isEmpty() ? "user" : "agent",
                scope,
                record.createdAt(),
                record.lastUsedAt());
    }

    private List<String> readScope(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            return List.of();
        }
    }

    private String writeJson(List<String> scope) {
        try {
            return objectMapper.writeValueAsString(scope);
        } catch (JsonProcessingException error) {
            throw new ServiceException("could not encode api key scope", error, BaseErrorCode.SERVICE_ERROR);
        }
    }

    /**
     * Key 明文 SHA-256（高熵随机串，无需慢散列）
     */
    static String hash(String rawKey) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 not available", error);
        }
    }
}
