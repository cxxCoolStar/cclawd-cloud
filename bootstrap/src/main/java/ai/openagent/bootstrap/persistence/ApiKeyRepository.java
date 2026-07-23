package ai.openagent.bootstrap.persistence;

import ai.openagent.bootstrap.identity.dao.entity.ApiKeyDO;
import ai.openagent.bootstrap.identity.dao.mapper.ApiKeyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * API Key 仓储。
 *
 * <p>
 * 外部继续暴露领域侧 ApiKeyRecord；内部落到 MyBatis-Plus DO + Mapper，
 * 作为从手写 JDBC Repository 迁回 ragent 持久层风格的第一组样例。
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class ApiKeyRepository {

    private final ApiKeyMapper apiKeyMapper;

    /**
     * 插入 Key
     */
    public void insert(ApiKeyRecord key) {
        apiKeyMapper.insert(toDO(key));
    }

    /**
     * 按散列查询（认证路径）
     */
    public Optional<ApiKeyRecord> findByHash(String keyHash) {
        return Optional.ofNullable(apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKeyDO>()
                .eq(ApiKeyDO::getKeyHash, keyHash)))
                .map(this::toRecord);
    }

    /**
     * 查询用户的 Key 列表（按创建时间升序）
     */
    public List<ApiKeyRecord> listByUser(String userId) {
        return apiKeyMapper.selectList(new LambdaQueryWrapper<ApiKeyDO>()
                .eq(ApiKeyDO::getUserId, userId)
                .orderByAsc(ApiKeyDO::getCreatedAt))
                .stream()
                .map(this::toRecord)
                .toList();
    }

    /**
     * 删除用户的指定 Key；返回是否删除成功（不存在或不属于该用户返回 false）
     */
    public boolean delete(String id, String userId) {
        return apiKeyMapper.delete(new LambdaQueryWrapper<ApiKeyDO>()
                .eq(ApiKeyDO::getId, id)
                .eq(ApiKeyDO::getUserId, userId)) > 0;
    }

    /**
     * 更新最近使用时间（认证成功时惰性刷新）
     */
    public void touchLastUsed(String id, long now) {
        ApiKeyDO update = new ApiKeyDO();
        update.setLastUsedAt(now);
        apiKeyMapper.update(update, new LambdaUpdateWrapper<ApiKeyDO>()
                .eq(ApiKeyDO::getId, id));
    }

    private ApiKeyDO toDO(ApiKeyRecord record) {
        ApiKeyDO key = new ApiKeyDO();
        key.setId(record.id());
        key.setUserId(record.userId());
        key.setName(record.name());
        key.setKeyHash(record.keyHash());
        key.setAgentIdsJson(record.agentIdsJson());
        key.setCreatedAt(record.createdAt());
        key.setLastUsedAt(record.lastUsedAt());
        return key;
    }

    private ApiKeyRecord toRecord(ApiKeyDO key) {
        return new ApiKeyRecord(
                key.getId(),
                key.getUserId(),
                key.getName(),
                key.getKeyHash(),
                key.getAgentIdsJson(),
                key.getCreatedAt(),
                key.getLastUsedAt());
    }
}