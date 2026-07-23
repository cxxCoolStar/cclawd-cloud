package ai.openagent.bootstrap.identity.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("api_keys")
public class ApiKeyDO {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    @TableField("user_id")
    private String userId;

    @TableField("name")
    private String name;

    @TableField("key_hash")
    private String keyHash;

    @TableField("agent_ids")
    private String agentIdsJson;

    @TableField("created_at")
    private Long createdAt;

    @TableField("last_used_at")
    private Long lastUsedAt;
}