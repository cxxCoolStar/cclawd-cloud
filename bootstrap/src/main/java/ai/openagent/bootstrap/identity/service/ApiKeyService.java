package ai.openagent.bootstrap.identity.service;

import ai.openagent.bootstrap.identity.controller.vo.ApiKeyVO;
import ai.openagent.framework.identity.RequestIdentity;
import java.util.List;
import java.util.Optional;

/**
 * API Key 服务（V9 M2）
 *
 * <p>
 * Key 明文只在创建时返回一次，库中只存 SHA-256 散列；认证路径同时承担
 * 最近使用时间的惰性刷新
 * </p>
 */
public interface ApiKeyService {

    /**
     * 创建结果：视图对象 + 仅此一次返回的明文 Key
     *
     * @param apikey Key 视图（key 字段为打码形态）
     * @param token  明文 Key
     */
    record CreatedApiKey(ApiKeyVO apikey, String token) {}

    /**
     * 创建 Key；agentIds 非空时逐一校验归属当前用户（越权 404）
     */
    CreatedApiKey create(String name, List<String> agentIds);

    /**
     * 列出当前用户的 Key（key 字段打码）
     */
    List<ApiKeyVO> list();

    /**
     * 删除当前用户的指定 Key；不存在或不属于当前用户返回 404
     */
    void delete(String id);

    /**
     * 按明文 Key 认证：命中且属主账号有效时返回携带 agent 子集的身份
     */
    Optional<RequestIdentity> authenticate(String rawKey);
}
